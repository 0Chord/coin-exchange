package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.order.Side
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

internal const val DIRECT_AERON_CAPACITY_RESULT_PREFIX = "DIRECT_AERON_CAPACITY_RESULT "

/** A row at a time after the load; does not serialize the entire cohort to a String or stdout. */
internal fun writeDirectAeronSustainedSamples(
    path: Path,
    runId: String,
    samples: List<DirectAeronCapacitySample>,
): Map<String, Any> {
    require(path.isAbsolute && path.fileName.toString() == "direct-client-samples.jsonl")
    require(runId.matches(Regex("[a-z0-9-]{1,64}")) && samples.size <= DIRECT_AERON_SUSTAINED_MAX_ORDERS)
    val mapper = jacksonObjectMapper()
    Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
        samples.forEachIndexed { index, sample ->
            writer.appendLine(
                mapper.writeValueAsString(
                    mapOf(
                        "kind" to "DIRECT_AERON_CAPACITY_SAMPLE",
                        "schemaVersion" to 1,
                        "runId" to runId,
                        "index" to index,
                        "sample" to sample,
                    ),
                ),
            )
        }
    }
    return directAeronSustainedArtifact(path, runId, "DIRECT_AERON_CAPACITY_SAMPLES_JSONL", samples.size)
}

internal fun writeDirectAeronSustainedErrors(
    path: Path,
    runId: String,
    result: DirectAeronCapacityResult?,
    startupFailure: String?,
    rawFailure: String?,
): Map<String, Any> {
    require(path.isAbsolute && path.fileName.toString() == "direct-client-errors.jsonl")
    require(runId.matches(Regex("[a-z0-9-]{1,64}")))
    val mapper = jacksonObjectMapper()
    var records = 0
    Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
        fun record(
            kind: String,
            message: String?,
        ) {
            if (message == null) return
            writer.appendLine(mapper.writeValueAsString(mapOf("runId" to runId, "index" to records, "kind" to kind, "message" to message)))
            records++
        }
        record("STARTUP_FAILURE", startupFailure)
        record("LOAD_FAILURE", result?.loadFailure)
        record("CLOSE_FAILURE", result?.closeFailure)
        record("RAW_FAILURE", rawFailure)
        result?.errors?.forEach { record("LOAD_ERROR", it) }
    }
    return directAeronSustainedArtifact(path, runId, "DIRECT_AERON_CAPACITY_ERRORS_JSONL", records)
}

private fun directAeronSustainedArtifact(
    path: Path,
    runId: String,
    kind: String,
    records: Int,
): Map<String, Any> {
    // No reference is returned until close has completed and the complete file was hashed.
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return mapOf(
        "kind" to kind,
        "schemaVersion" to 1,
        "artifactFile" to path.fileName.toString(),
        "runId" to runId,
        "records" to records,
        "bytes" to Files.size(path),
        "sha256" to HexFormat.of().formatHex(digest.digest()),
        "complete" to true,
    )
}

internal fun parseDirectAeronCapacityClientArguments(args: Array<String>): DirectAeronCapacityWorkload {
    if (args.firstOrNull() == "--sustained") {
        require(args.size == 6 && args[1].startsWith("--rate=") && args[2].startsWith("--seconds=")) {
            "장기 client: --sustained --rate=1000 --seconds=60 prefix user maxInFlight"
        }
        val rate = requireNotNull(args[1].removePrefix("--rate=").toIntOrNull())
        val seconds = requireNotNull(args[2].removePrefix("--seconds=").toIntOrNull())
        return DirectAeronCapacityWorkload(
            rate,
            directAeronSustainedCount(rate, seconds),
            args[3],
            Side.BUY,
            args[4],
            requireNotNull(args[5].toIntOrNull()),
            sustainedSeconds = seconds,
        )
    }
    require(args.size == 5) { "직접 Aeron 접수 burst: rate count prefix user maxInFlight" }
    return DirectAeronCapacityWorkload(
        rate = requireNotNull(args[0].toIntOrNull()) { "rate 정수가 필요합니다" },
        count = requireNotNull(args[1].toIntOrNull()) { "count 정수가 필요합니다" },
        prefix = args[2],
        side = Side.BUY,
        userId = args[3],
        maxInFlight = requireNotNull(args[4].toIntOrNull()) { "maxInFlight 정수가 필요합니다" },
    )
}

private fun directAeronCapacityClientIdentities(result: DirectAeronCapacityResult): Boolean {
    if (result.workload.sustained) {
        for (index in result.samples.indices) if (result.samples[index].command.requestId != "M-$index") return false
        return true
    }
    return result.samples
        .map { it.command.requestId }
        .distinct()
        .size == result.planned
}

internal fun directAeronCapacityClientSucceeded(result: DirectAeronCapacityResult): Boolean =
    (
        !result.workload.sustained ||
            (
                result.windowEndNs - result.firstDueNs == result.workload.windowNs &&
                    directAeronCapacityWindowEvidence(result).let { it.windowElapsed && it.allPlannedSendsInsideWindow }
            )
    ) &&
        result.planned == result.workload.count && result.started == result.planned && result.notSent == 0 &&
        result.transportAccepted == result.planned && result.offerUnknown == 0 && result.resultUnknown == 0 &&
        result.accepted == result.planned && result.rejected == 0 && result.errors.isEmpty() && result.errorOverflow == 0L &&
        result.loadFailure == null && result.closeFailure == null && result.samples.size == result.planned &&
        directAeronCapacityClientIdentities(result) &&
        result.samples.all { sample ->
            val response = sample.response
            sample.outcome == DirectAeronCapacityOutcome.ACCEPTED && sample.transportAcceptedNs != null && sample.ackNs != null &&
                response is AdmissionResultPayload.Accepted && response.sequence > 0 &&
                response.userId == sample.command.order.userId.value && response.requestId == sample.command.requestId &&
                response.marketId == sample.command.order.marketId.value && response.orderId == sample.command.order.orderId.value
        }

internal fun readDirectAeronCapacityClientReport(stdout: String): JsonNode {
    val records = stdout.lineSequence().filter { it.startsWith(DIRECT_AERON_CAPACITY_RESULT_PREFIX) }.toList()
    require(records.size == 1) { "직접 Aeron client 결과 JSON이 정확히 한 건 필요합니다: ${records.size}" }
    return jacksonObjectMapper().readTree(records.single().removePrefix(DIRECT_AERON_CAPACITY_RESULT_PREFIX)).also {
        require(it.path("kind").asString() == "DIRECT_AERON_ADMISSION_BURST" && it.path("schemaVersion").asInt() == 1)
    }
}

internal fun directAeronSampleProvesNotSent(sample: JsonNode): Boolean {
    val lastOffer = sample.path("lastOfferResult")
    return sample.path("outcome").asString() == "NOT_SENT" && sample.path("transportAcceptedNs").isNull &&
        sample.path("transportPosition").isNull && sample.path("ackNs").isNull &&
        (lastOffer.isNull || lastOffer.isMissingNode || lastOffer.asLong() <= 0)
}

/** 시작 실패도 load 진입 전임을 확인해야 0전송으로 판단한다. 불확인 offer가 하나라도 있으면 false다. */
internal fun directAeronClientHadNoAcceptedOffer(
    report: JsonNode,
    orders: Int,
): Boolean {
    val result = report.path("result")
    if (!report.path("startupFailure").isNull && !report.path("startupFailure").isMissingNode &&
        report.path("loadStarted").isBoolean && !report.path("loadStarted").asBoolean() && (result.isNull || result.isMissingNode)
    ) {
        return true
    }
    if (result.isNull || result.isMissingNode) return false
    val samples = result.path("samples").toList()
    return result.path("planned").asInt() == orders && result.path("notSent").asInt() == orders &&
        result.path("transportAccepted").asInt() == 0 && result.path("offerUnknown").asInt() == 0 &&
        result.path("resultUnknown").asInt() == 0 && result.path("accepted").asInt() == 0 && result.path("rejected").asInt() == 0 &&
        samples.size == orders && samples.all(::directAeronSampleProvesNotSent)
}

/** 확실히 실행되지 않은 행만 제외한다. 불완전한 보고나 전송·결과 불확인은 보수적으로 기다린다. */
internal fun directAeronPossibleExecutionIds(
    report: JsonNode,
    orders: Int,
): Set<String> {
    require(orders in 1..2000)
    val plannedIds = (0 until orders).map { "M-$it" }.toSet()
    if (directAeronClientHadNoAcceptedOffer(report, orders)) return emptySet()
    val result = report.path("result")
    val samples = result.path("samples").toList()
    val sampleIds = samples.map { it.path("command").path("requestId").asString() }
    if (result.path("planned").asInt() != orders || samples.size != orders || sampleIds.toSet() != plannedIds) return plannedIds
    val counts = samples.groupingBy { it.path("outcome").asString() }.eachCount()
    if (counts.keys.any { it !in DirectAeronCapacityOutcome.entries.map { outcome -> outcome.name } } ||
        result.path("notSent").asInt() != (counts["NOT_SENT"] ?: 0) ||
        result.path("accepted").asInt() != (counts["ACCEPTED"] ?: 0) ||
        result.path("rejected").asInt() != (counts["REJECTED"] ?: 0) ||
        result.path("offerUnknown").asInt() != (counts["OFFER_UNKNOWN"] ?: 0) ||
        result.path("resultUnknown").asInt() != (counts["OFFER_UNKNOWN"] ?: 0) + (counts["RESULT_UNKNOWN"] ?: 0)
    ) {
        return plannedIds
    }
    return samples
        .filterNot { sample ->
            when (sample.path("outcome").asString()) {
                "NOT_SENT" -> {
                    directAeronSampleProvesNotSent(sample)
                }

                "REJECTED" -> {
                    val response = sample.path("response")
                    val requestId = sample.path("command").path("requestId").asString()
                    !sample.path("ackNs").isNull && !sample.path("ackNs").isMissingNode &&
                        !sample.path("transportAcceptedNs").isNull && !sample.path("transportAcceptedNs").isMissingNode &&
                        !response.path("reason").isMissingNode && !response.path("reason").isNull &&
                        response.path("requestId").asString() == requestId && response.path("orderId").asString() == requestId &&
                        response.path("userId").asString() == "BUYER" && response.path("marketId").asString() == "BTC-USDT"
                }

                else -> {
                    false
                }
            }
        }.map { it.path("command").path("requestId").asString() }
        .toSet()
}

/** 별도 client JVM의 접수 ACK만 잰다. 부모 관측 시계와의 차분이나 실행 완료 TPS는 만들지 않는다. */
fun main(args: Array<String>) {
    var workload: DirectAeronCapacityWorkload? = null
    var config: DevelopmentFundingSettings? = null
    var result: DirectAeronCapacityResult? = null
    var startupFailure: String? = null
    var loadStarted = false
    var rawPath: Path? = null
    try {
        workload = parseDirectAeronCapacityClientArguments(args)
        if (workload.sustained) {
            rawPath = Path.of(requireNotNull(System.getenv("DIRECT_AERON_RAW_FILE")))
            require(rawPath.isAbsolute && rawPath.fileName.toString() == "direct-client-samples.jsonl")
            require(Files.isDirectory(rawPath.parent) && Files.isWritable(rawPath.parent) && !Files.exists(rawPath)) {
                "장기 raw 경로가 없거나 쓸 수 없거나 이미 존재합니다"
            }
            require(!Files.exists(rawPath.resolveSibling("direct-client-errors.jsonl")))
            requireNotNull(System.getenv("FUNDING_RUN_ID"))
        }
        config = developmentClusterSettings()
        loadStarted = true
        result = DirectAeronCapacityLoad(config, workload).run()
    } catch (failure: Exception) {
        startupFailure = failure.stackTraceToString()
    }
    var rawArtifact: Map<String, Any>? = null
    var rawFailure: String? = null
    var errorArtifact: Map<String, Any>? = null
    var errorArtifactFailure: String? = null
    if (workload?.sustained == true) {
        try {
            rawArtifact =
                writeDirectAeronSustainedSamples(
                    checkNotNull(rawPath),
                    requireNotNull(System.getenv("FUNDING_RUN_ID")),
                    result?.samples.orEmpty(),
                )
        } catch (failure: Exception) {
            rawFailure = failure.stackTraceToString()
        }
        try {
            errorArtifact =
                writeDirectAeronSustainedErrors(
                    checkNotNull(rawPath).resolveSibling("direct-client-errors.jsonl"),
                    requireNotNull(System.getenv("FUNDING_RUN_ID")),
                    result,
                    startupFailure,
                    rawFailure,
                )
        } catch (failure: Exception) {
            errorArtifactFailure = failure.stackTraceToString()
            // Keep full details in the already owned stderr when the dedicated evidence file failed.
            listOfNotNull(
                startupFailure,
                result?.loadFailure,
                result?.closeFailure,
                rawFailure,
                errorArtifactFailure,
            ).forEach(System.err::println)
            result?.errors?.forEach(System.err::println)
        }
    }
    val succeeded =
        startupFailure == null && rawFailure == null && errorArtifactFailure == null &&
            result?.let(::directAeronCapacityClientSucceeded) == true
    val summaryResult =
        if (workload?.sustained == true) {
            result?.let { value ->
                value.copy(
                    samples = emptyList(),
                    errors = value.errors.take(16).map { it.take(1024) },
                    loadFailure = value.loadFailure?.take(4096),
                    closeFailure = value.closeFailure?.take(4096),
                )
            }
        } else {
            result
        }
    val report =
        mapOf(
            "schemaVersion" to if (workload?.sustained == true) 2 else 1,
            "kind" to if (workload?.sustained == true) "DIRECT_AERON_ADMISSION_SUSTAINED" else "DIRECT_AERON_ADMISSION_BURST",
            "runId" to System.getenv("FUNDING_RUN_ID"),
            "clock" to "client JVM System.nanoTime only",
            "sustainedThroughputClaim" to false,
            "completionLatencyCalculated" to false,
            "workload" to workload,
            "settings" to
                config?.let {
                    mapOf(
                        "runRoot" to it.runRoot.toString(),
                        "ingressEndpoints" to it.ingressEndpoints,
                        "egressEndpoint" to it.endpoint(7),
                        "resultTimeoutMs" to it.resultTimeoutMs,
                        "keepAliveMs" to it.keepAliveMs,
                    )
                },
            "jvm" to
                mapOf(
                    "pid" to ProcessHandle.current().pid(),
                    "javaVersion" to System.getProperty("java.version"),
                    "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
                    "availableProcessors" to Runtime.getRuntime().availableProcessors(),
                    "arguments" to ManagementFactory.getRuntimeMXBean().inputArguments,
                ),
            "startupFailure" to if (workload?.sustained == true) startupFailure?.take(4096) else startupFailure,
            "loadStarted" to loadStarted,
            "succeeded" to succeeded,
            "result" to summaryResult,
            "windowEvidence" to result?.takeIf { it.workload.sustained }?.let(::directAeronCapacityWindowEvidence),
            "rawArtifact" to rawArtifact,
            "rawFailure" to rawFailure?.take(4096),
            "errorArtifact" to errorArtifact,
            "errorArtifactFailure" to errorArtifactFailure?.take(4096),
            "originalErrorCount" to result?.errors?.size,
            "summaryOmittedErrorDetails" to result?.errors?.size?.let { (it - 16).coerceAtLeast(0) },
            "rawRetentionScope" to
                "full per-ID samples retained through load completion; JSONL avoids one giant stdout JSON, not a client heap-fit guarantee",
        )
    println(DIRECT_AERON_CAPACITY_RESULT_PREFIX + jacksonObjectMapper().writeValueAsString(report))
    check(succeeded) {
        "직접 Aeron ${if (workload?.sustained == true) "60초 paced 접수" else "접수 burst"}에서 예정 전량 ACK·무오류 조건을 확인하지 못했습니다. 원문과 미확인 상태를 보존했습니다"
    }
}
