package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 60 tiny synthetic raw rows, no client/container execution and no trading performance verdict. */
class DirectAeronSustainedRawFilesTest {
    @TempDir
    lateinit var reports: Path
    private val mapper = jacksonObjectMapper()
    private val workload =
        parseDirectAeronCapacityClientArguments(arrayOf("--sustained", "--rate=1", "--seconds=60", "M-", "BUYER", "4096"))
    private var serial = 0

    @Test
    fun `닫힌 JSONL 전체행 identity bytes SHA를 한 pass로 검증하고 작은참조만 반환한다`() {
        val evidence = fixture()
        val indexes = mutableListOf<Int>()
        val check =
            DirectAeronSustainedRawFiles.scan(evidence.resolve(), workload) { index, sample ->
                indexes.add(index)
                assertEquals("M-$index", sample.at("/command/requestId").asString())
                assertEquals("AQID", sample.path("encodedCommand").asString())
            }
        assertEquals((0 until workload.count).toList(), indexes)
        assertEquals(workload.count, check.records)
        assertEquals(Files.size(evidence.file), check.bytes)
        assertEquals(sha(evidence.file), check.sha256)
        val fields = mapper.valueToTree<JsonNode>(check.fields(reports))
        assertTrue(fields.path("verified").asBoolean())
        assertTrue(fields.path("samples").isMissingNode)
        assertTrue(mapper.writeValueAsString(fields).length < 1024)
        assertTrue(Files.isRegularFile(evidence.file))
    }

    @Test
    fun `stdout은 sustained schema와 planned입력만받고 fullsamples와중복summary는거절한다`() {
        val evidence = fixture()
        val stdout = DIRECT_AERON_CAPACITY_RESULT_PREFIX + mapper.writeValueAsString(evidence.report)
        assertEquals("raw-test", DirectAeronSustainedRawFiles.readReport(stdout, "raw-test", workload).path("runId").asString())
        assertFailsWith<IllegalArgumentException> { DirectAeronSustainedRawFiles.readReport("$stdout\n$stdout", "raw-test", workload) }
        val wrong = evidence.report.deepCopy() as ObjectNode
        wrong.put("schemaVersion", "2")
        assertFailsWith<IllegalArgumentException> {
            DirectAeronSustainedRawFiles.readReport(
                DIRECT_AERON_CAPACITY_RESULT_PREFIX + mapper.writeValueAsString(wrong),
                "raw-test",
                workload,
            )
        }
        (evidence.report.path("result") as ObjectNode).putArray("samples").addObject().put("unbounded", true)
        assertFailsWith<IllegalArgumentException> {
            DirectAeronSustainedRawFiles.readReport(
                DIRECT_AERON_CAPACITY_RESULT_PREFIX + mapper.writeValueAsString(evidence.report),
                "raw-test",
                workload,
            )
        }
    }

    @Test
    fun `0행은명시된load이전실패에서만허용하며실행성공으로바꾸지않는다`() {
        val evidence = fixture(startup = true)
        val check =
            DirectAeronSustainedRawFiles.scan(
                evidence.resolve(),
                workload,
            ) { _, _ -> error("startup failure has no fabricated samples") }
        assertEquals(0, check.records)
        assertEquals(0L, check.bytes)
        assertTrue(check.reference.startupZeroRows)
        assertFalse(evidence.report.path("succeeded").asBoolean())
        evidence.report.put("loadStarted", true)
        assertFailsWith<IllegalArgumentException> { evidence.resolve() }
        evidence.report.put("loadStarted", "false")
        assertFailsWith<IllegalArgumentException> { evidence.resolve() }
    }

    @Test
    fun `raw 파일명 run schema complete bytes와writer실패를coercion없이검사한다`() {
        val mutations: List<(ObjectNode) -> Unit> =
            listOf(
                { it.put("artifactFile", "../direct-client-samples.jsonl") },
                { it.put("runId", "other-run") },
                { it.put("schemaVersion", "1") },
                { it.remove("complete") },
                { it.put("bytes", -1) },
                { it.put("records", "60") },
            )
        for (mutate in mutations) {
            val evidence = fixture()
            mutate(evidence.artifact())
            assertFailsWith<IllegalArgumentException> { evidence.resolve() }
            assertTrue(Files.isRegularFile(evidence.file))
        }
        val evidence = fixture()
        evidence.report.put("rawFailure", "writer close failed")
        assertFailsWith<IllegalArgumentException> { evidence.resolve() }
    }

    @Test
    fun `index와명령의모든planned식별자가틀리면개수와hash가맞아도실패한다`() {
        val changes =
            listOf<Pair<String, Any>>(
                "/index" to 1,
                "/sample/command/requestId" to "M-X",
                "/sample/command/order/orderId" to "OTHER",
                "/sample/command/order/userId" to "OTHER",
                "/sample/command/order/price" to 9_999,
            )
        for ((path, value) in changes) {
            val evidence =
                fixture { index, row ->
                    if (index == 0) {
                        val parent = row.at(path.substringBeforeLast('/')) as ObjectNode
                        if (value is Int) {
                            parent.put(
                                path.substringAfterLast('/'),
                                value,
                            )
                        } else {
                            parent.put(path.substringAfterLast('/'), value as String)
                        }
                    }
                    mapper.writeValueAsString(row)
                }
            assertFailsWith<IllegalArgumentException> { DirectAeronSustainedRawFiles.scan(evidence.resolve(), workload) }
        }
    }

    @Test
    fun `중복JSON필드와행뒤추가JSON값을모두거절한다`() {
        for (suffix in listOf("duplicate", "trailing")) {
            val evidence =
                fixture { index, row ->
                    val line = mapper.writeValueAsString(row)
                    if (index != 0) {
                        line
                    } else if (suffix == "duplicate") {
                        line.replace("\"index\":0", "\"index\":0,\"index\":0")
                    } else {
                        "$line {}"
                    }
                }
            assertFailsWith<Exception> { DirectAeronSustainedRawFiles.scan(evidence.resolve(), workload) }
        }
    }

    @Test
    fun `마지막LF 누락과행누락추가를부분성공으로반환하지않는다`() {
        for (change in listOf<
            (
                String,
            ) -> String,
        >({ it.dropLast(1) }, { it.substringAfter('\n') }, { it + it.substringBefore('\n') + "\n" })) {
            val evidence = fixture()
            evidence.rewrite(change(Files.readString(evidence.file)).toByteArray())
            assertFailsWith<IllegalArgumentException> { DirectAeronSustainedRawFiles.scan(evidence.resolve(), workload) }
            assertTrue(Files.isRegularFile(evidence.file))
        }
    }

    @Test
    fun `같은길이변조도실독SHA에서거절하고잘못된UTF8과행상한도거절한다`() {
        val evidence = fixture()
        val ref = evidence.resolve()
        val original = Files.readString(evidence.file)
        Files.writeString(evidence.file, original.replace("AQID", "AQIE"))
        assertFailsWith<IllegalArgumentException> { DirectAeronSustainedRawFiles.scan(ref, workload) }
        val invalidUtf8 = fixture()
        val bytes = Files.readAllBytes(invalidUtf8.file)
        bytes[0] = 0xc3.toByte()
        bytes[1] = 0x28
        invalidUtf8.rewrite(bytes)
        assertFailsWith<Exception> { DirectAeronSustainedRawFiles.scan(invalidUtf8.resolve(), workload) }
        val large =
            fixture { index, row ->
                if (index == 0) row.put("padding", "x".repeat(65_537))
                mapper.writeValueAsString(row)
            }
        assertFailsWith<IllegalArgumentException> { DirectAeronSustainedRawFiles.scan(large.resolve(), workload) }
    }

    @Test
    fun `마지막consumer interrupt와일반예외는성공이되지않고원문은유지한다`() {
        val evidence = fixture()
        try {
            assertFailsWith<InterruptedException> {
                DirectAeronSustainedRawFiles.scan(evidence.resolve(), workload) { index, _ ->
                    if (index ==
                        workload.count - 1
                    ) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        val failure = IllegalStateException("consumer failed")
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                DirectAeronSustainedRawFiles.scan(evidence.resolve(), workload) { _, _ -> throw failure }
            },
        )
        assertTrue(Files.isRegularFile(evidence.file))
    }

    @Test
    fun `compose bridge는기존JVM예산과유일endpoint를유지하고ownedrawbind만추가한다`() {
        val evidence = fixture()
        val args = directAeronSustainedComposeArguments("raw-test-direct-1", evidence.file.parent, workload)
        assertTrue(args.contains("JAVA_TOOL_OPTIONS=-Xms256m -Xmx2g"))
        assertTrue(args.contains("FUNDING_HOST=raw-test-direct-1"))
        assertTrue(args.contains("FUNDING_RUN_ROOT=/tmp/raw-test-direct-1"))
        assertTrue(args.contains("${evidence.file.parent}:/direct-capacity-evidence:rw"))
        assertTrue(args.contains("DIRECT_AERON_RAW_FILE=/direct-capacity-evidence/direct-client-samples.jsonl"))
        assertEquals(listOf("--sustained", "--rate=1", "--seconds=60", "M-", "BUYER", "4096"), args.takeLast(6))
        assertFalse(args.contains("--cpus"))
    }

    private fun fixture(
        startup: Boolean = false,
        line: (Int, ObjectNode) -> String = { _, value -> mapper.writeValueAsString(value) },
    ): Fixture {
        val directory = Files.createDirectories(reports.resolve("control/direct-test-${serial++}"))
        val file = directory.resolve(DIRECT_AERON_SUSTAINED_RAW_NAME)
        val lines =
            if (startup) {
                ""
            } else {
                (0 until workload.count).joinToString(separator = "", transform = { index ->
                    val sample =
                        mapOf(
                            "command" to
                                mapOf(
                                    "requestId" to "M-$index",
                                    "order" to
                                        mapOf(
                                            "orderId" to "M-$index",
                                            "userId" to "BUYER",
                                            "marketId" to "BTC-USDT",
                                            "side" to "BUY",
                                            "orderType" to "LIMIT",
                                            "timeInForce" to "GTC",
                                            "price" to 10_000L,
                                            "quantity" to 100_000_000L,
                                        ),
                                ),
                            "encodedCommand" to "AQID",
                            "outcome" to "NOT_SENT",
                            "response" to null,
                        )
                    val row =
                        mapper.valueToTree<ObjectNode>(
                            mapOf(
                                "kind" to "DIRECT_AERON_CAPACITY_SAMPLE",
                                "schemaVersion" to 1,
                                "runId" to "raw-test",
                                "index" to index,
                                "sample" to sample,
                            ),
                        )
                    line(index, row) + "\n"
                })
            }
        Files.writeString(file, lines)
        val report =
            mapper.valueToTree<ObjectNode>(
                mapOf(
                    "kind" to "DIRECT_AERON_ADMISSION_SUSTAINED",
                    "schemaVersion" to 2,
                    "runId" to "raw-test",
                    "workload" to workload,
                    "startupFailure" to if (startup) "startup failed" else null,
                    "loadStarted" to !startup,
                    "succeeded" to false,
                    "rawFailure" to null,
                    "result" to if (startup) null else mapOf("planned" to workload.count, "samples" to emptyList<Any>()),
                    "rawArtifact" to
                        mapOf(
                            "kind" to "DIRECT_AERON_CAPACITY_SAMPLES_JSONL",
                            "schemaVersion" to 1,
                            "artifactFile" to DIRECT_AERON_SUSTAINED_RAW_NAME,
                            "runId" to "raw-test",
                            "records" to if (startup) 0 else workload.count,
                            "bytes" to Files.size(file),
                            "sha256" to sha(file),
                            "complete" to true,
                        ),
                ),
            )
        return Fixture(file, report)
    }

    private inner class Fixture(
        val file: Path,
        val report: ObjectNode,
    ) {
        fun artifact(): ObjectNode = report.path("rawArtifact") as ObjectNode

        fun resolve(): DirectAeronSustainedRawReference = DirectAeronSustainedRawFiles.resolve(file.parent, report, "raw-test", workload)

        fun rewrite(bytes: ByteArray) {
            Files.write(file, bytes)
            artifact().put("bytes", bytes.size)
            artifact().put("sha256", sha(file))
        }
    }

    private fun sha(file: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
}
