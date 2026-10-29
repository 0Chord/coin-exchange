package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal const val DIRECT_AERON_SUSTAINED_RAW_NAME = "direct-client-samples.jsonl"
private const val MAX_RAW_LINE_BYTES = 65_536
private const val MAX_SUSTAINED_STDOUT_CHARS = 262_144

/** An owned host file identity, not retained samples or a throughput verdict. */
internal data class DirectAeronSustainedRawReference(
    val file: Path,
    val runId: String,
    val records: Int,
    val bytes: Long,
    val sha256: String,
    val startupZeroRows: Boolean,
) {
    init {
        require(file.isAbsolute && file.fileName.toString() == DIRECT_AERON_SUSTAINED_RAW_NAME)
        require(records >= 0 && bytes >= 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Returned only after complete UTF-8 JSONL, row identity, terminal LF, bytes and SHA verification. */
internal data class DirectAeronSustainedRawCheck(
    val reference: DirectAeronSustainedRawReference,
    val records: Int,
    val bytes: Long,
    val sha256: String,
) {
    fun fields(reports: Path): Map<String, Any?> {
        val root = reports.toAbsolutePath().normalize()
        require(reference.file.normalize().startsWith(root.resolve("control")))
        return mapOf(
            "artifactFile" to root.relativize(reference.file.normalize()).toString(),
            "runId" to reference.runId,
            "records" to records,
            "bytes" to bytes,
            "sha256" to sha256,
            "startupZeroRows" to reference.startupZeroRows,
            "verified" to true,
            "scope" to "complete raw file and planned command identity; not a financial or throughput verdict",
        )
    }
}

/** All callbacks are provisional until scan returns. Never retain all JsonNode samples. */
internal object DirectAeronSustainedRawFiles {
    private val mapper =
        JsonMapper
            .builder(
                JsonFactory
                    .builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                        StreamReadConstraints
                            .builder()
                            .maxNestingDepth(
                                64,
                            ).maxNameLength(256)
                            .maxStringLength(MAX_RAW_LINE_BYTES)
                            .maxNumberLength(128)
                            .build(),
                    ).build(),
            ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    fun readReport(
        stdout: String,
        runId: String,
        workload: DirectAeronCapacityWorkload,
    ): JsonNode {
        require(workload.sustained && stdout.length <= MAX_SUSTAINED_STDOUT_CHARS) { "장기 client stdout 상한/모드 오류" }
        val records = stdout.lineSequence().filter { it.startsWith(DIRECT_AERON_CAPACITY_RESULT_PREFIX) }.iterator()
        require(records.hasNext()) { "장기 client summary가 없습니다" }
        val line = records.next().removePrefix(DIRECT_AERON_CAPACITY_RESULT_PREFIX)
        require(!records.hasNext()) { "장기 client summary가 중복됐습니다" }
        val report = mapper.readTree(line)
        require(report.isObject)
        text(report, "/kind", "DIRECT_AERON_ADMISSION_SUSTAINED")
        integer(report, "/schemaVersion", 2)
        text(report, "/runId", runId)
        val plan = report.path("workload")
        require(plan.isObject) { "장기 client workload 누락" }
        integer(plan, "/rate", workload.rate.toLong())
        integer(plan, "/count", workload.count.toLong())
        integer(plan, "/sustainedSeconds", checkNotNull(workload.sustainedSeconds).toLong())
        integer(plan, "/maxInFlight", workload.maxInFlight.toLong())
        text(plan, "/prefix", workload.prefix)
        text(plan, "/userId", workload.userId)
        text(plan, "/side", workload.side.name)
        text(plan, "/marketId", workload.marketId.value)
        integer(plan, "/price", workload.price)
        integer(plan, "/quantity", workload.quantity)
        val result = report.path("result")
        require(result.isNull || (result.isObject && result.path("samples").isArray && result.path("samples").isEmpty)) {
            "장기 stdout에 full samples를 넣을 수 없습니다"
        }
        return report
    }

    fun resolve(
        directory: Path,
        report: JsonNode,
        runId: String,
        workload: DirectAeronCapacityWorkload,
    ): DirectAeronSustainedRawReference {
        require(workload.sustained)
        text(report, "/kind", "DIRECT_AERON_ADMISSION_SUSTAINED")
        integer(report, "/schemaVersion", 2)
        text(report, "/runId", runId)
        require(report.path("rawFailure").isNull) { "client raw writer 실패 또는 상태 누락" }
        val artifact = report.path("rawArtifact")
        text(artifact, "/kind", "DIRECT_AERON_CAPACITY_SAMPLES_JSONL")
        integer(artifact, "/schemaVersion", 1)
        text(artifact, "/runId", runId)
        text(artifact, "/artifactFile", DIRECT_AERON_SUSTAINED_RAW_NAME)
        require(artifact.path("complete").isBoolean && artifact.path("complete").asBoolean()) { "client raw close 완료 미확인" }
        val beforeLoadFailure =
            report.path("startupFailure").isString && report.path("startupFailure").asString().isNotEmpty() &&
                report.path("loadStarted").isBoolean && !report.path("loadStarted").asBoolean() && report.path("result").isNull &&
                report.path("succeeded").isBoolean && !report.path("succeeded").asBoolean()
        val records = long(artifact, "/records")
        require(records == if (beforeLoadFailure) 0L else workload.count.toLong()) { "client raw 전체 행 수 불일치" }
        val bytes = long(artifact, "/bytes")
        require(bytes >= 0 && (records == 0L || bytes > 0))
        val fingerprint = artifact.path("sha256")
        require(fingerprint.isString) { "client raw SHA 누락" }
        val owned = directory.toAbsolutePath().normalize()
        require(Files.isDirectory(owned, LinkOption.NOFOLLOW_LINKS))
        val reference =
            DirectAeronSustainedRawReference(
                owned.resolve(DIRECT_AERON_SUSTAINED_RAW_NAME),
                runId,
                records.toInt(),
                bytes,
                fingerprint.asString(),
                beforeLoadFailure,
            )
        require(Files.isRegularFile(reference.file, LinkOption.NOFOLLOW_LINKS) && Files.size(reference.file) == reference.bytes) {
            "client raw 파일 크기/종류 오류"
        }
        return reference
    }

    fun scan(
        reference: DirectAeronSustainedRawReference,
        workload: DirectAeronCapacityWorkload,
        onSample: (Int, JsonNode) -> Unit = { _, _ -> },
    ): DirectAeronSustainedRawCheck {
        interrupted()
        require(workload.sustained && reference.records == (if (reference.startupZeroRows) 0 else workload.count))
        require(Files.isRegularFile(reference.file, LinkOption.NOFOLLOW_LINKS) && Files.size(reference.file) == reference.bytes) {
            "client raw 파일 크기/종류 오류"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var records = 0
        val pending = ByteArrayOutputStream(4096)
        Files.newInputStream(reference.file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                interrupted()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                bytes = Math.addExact(bytes, count.toLong())
                var start = 0
                for (index in 0 until count) {
                    if (buffer[index] == '\n'.code.toByte()) {
                        append(pending, buffer, start, index - start)
                        require(pending.size() > 0 && records < reference.records) { "client raw 빈 행/예상 밖 추가 행" }
                        val decoder =
                            StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(
                                    CodingErrorAction.REPORT,
                                ).onUnmappableCharacter(CodingErrorAction.REPORT)
                        val row = mapper.readTree(decoder.decode(ByteBuffer.wrap(pending.toByteArray())).toString())
                        require(row.isObject)
                        text(row, "/kind", "DIRECT_AERON_CAPACITY_SAMPLE")
                        integer(row, "/schemaVersion", 1)
                        text(row, "/runId", reference.runId)
                        integer(row, "/index", records.toLong())
                        val sample = row.path("sample")
                        require(sample.isObject) { "client raw sample 누락" }
                        val expectedId = "${workload.prefix}$records"
                        text(sample, "/command/requestId", expectedId)
                        text(sample, "/command/order/orderId", expectedId)
                        text(sample, "/command/order/userId", workload.userId)
                        text(sample, "/command/order/marketId", workload.marketId.value)
                        text(sample, "/command/order/side", workload.side.name)
                        text(sample, "/command/order/orderType", "LIMIT")
                        text(sample, "/command/order/timeInForce", "GTC")
                        integer(sample, "/command/order/price", workload.price)
                        integer(sample, "/command/order/quantity", workload.quantity)
                        onSample(records, sample)
                        interrupted()
                        records = Math.incrementExact(records)
                        pending.reset()
                        start = index + 1
                    }
                }
                append(pending, buffer, start, count - start)
            }
        }
        interrupted()
        require(pending.size() == 0) { "client raw 마지막 JSONL 행의 LF/EOF 미완료" }
        require(records == reference.records && bytes == reference.bytes) { "client raw 실독 행 수/bytes 불일치" }
        val fingerprint = HexFormat.of().formatHex(digest.digest())
        require(fingerprint == reference.sha256) { "client raw 실독 SHA 불일치" }
        interrupted()
        return DirectAeronSustainedRawCheck(reference, records, bytes, fingerprint)
    }

    private fun append(
        output: ByteArrayOutputStream,
        source: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(length <= MAX_RAW_LINE_BYTES - output.size()) { "client raw 단일 행 bytes 상한 초과" }
        output.write(source, offset, length)
    }

    private fun text(
        root: JsonNode,
        pointer: String,
        expected: String,
    ) {
        val value = root.at(pointer)
        require(value.isString && value.asString() == expected) { "client raw 식별자 불일치: $pointer" }
    }

    private fun integer(
        root: JsonNode,
        pointer: String,
        expected: Long,
    ) {
        require(long(root, pointer) == expected) { "client raw 정수 불일치: $pointer" }
    }

    private fun long(
        root: JsonNode,
        pointer: String,
    ): Long {
        val value = root.at(pointer)
        require(value.isIntegralNumber && value.canConvertToLong()) { "client raw Long 정수 누락/범위 오류: $pointer" }
        return value.asLong()
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("direct sustained raw verification interrupted")
    }
}

/** Same client JVM/endpoint budget as burst; only explicit long args and the owned raw bind are added. */
internal fun directAeronSustainedComposeArguments(
    name: String,
    rawDirectory: Path,
    workload: DirectAeronCapacityWorkload,
): List<String> {
    require(name.matches(Regex("[a-z0-9-]{1,100}")) && rawDirectory.isAbsolute && workload.sustained)
    return listOf(
        "run",
        "--name",
        name,
        "--env",
        "FUNDING_HOST=$name",
        "--env",
        "FUNDING_RUN_ROOT=/tmp/$name",
        "--env",
        "JAVA_TOOL_OPTIONS=-Xms256m -Xmx2g",
        "--volume",
        "$rawDirectory:/direct-capacity-evidence:rw",
        "--env",
        "DIRECT_AERON_RAW_FILE=/direct-capacity-evidence/$DIRECT_AERON_SUSTAINED_RAW_NAME",
        "client",
        "com.exchange.core.api.trading.infrastructure.aeron.DirectAeronCapacityClientKt",
        "--sustained",
        "--rate=${workload.rate}",
        "--seconds=${checkNotNull(workload.sustainedSeconds)}",
        workload.prefix,
        workload.userId,
        workload.maxInFlight.toString(),
    )
}
