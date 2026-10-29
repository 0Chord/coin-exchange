package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Small parser fixtures only. No node process, large artifact, load, or financial verdict is used. */
class HttpPerformanceCheckpointReaderTest {
    @TempDir
    lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @Test
    fun `reference의 member 누락 null 문자열 소수와 범위 초과는 정상 영번으로 바꾸지 않는다`() {
        val ref = publish(fixture())
        assertEquals(ref, HttpPerformanceCheckpointReader.resolve(directory, event(ref), "reader-test", 0, "final"))
        for (value in listOf(null, "null", "\"0\"", "0.0", "4294967296", "false")) {
            val bad = event(ref).deepCopy() as ObjectNode
            if (value == null) bad.remove("memberId") else bad.set("memberId", mapper.readTree(value))
            assertFailsWith<IllegalArgumentException>("memberId=$value") {
                HttpPerformanceCheckpointReader.resolve(directory, bad, "reader-test", 0, "final")
            }
        }
    }

    @Test
    fun `마지막 구조 scalar와 row visitor가 취소하면 완전한 원문이어도 성공 반환하지 않는다`() {
        val previouslyInterrupted = Thread.interrupted()
        try {
            for (phase in listOf("structure", "scalar", "row")) {
                Thread.interrupted()
                val ref = raw(mapper.writeValueAsString(if (phase == "scalar") fixture() + ("tail" to 123) else fixture()))
                assertFailsWith<InterruptedException>(phase) {
                    HttpPerformanceCheckpointReader.scanRows(
                        ref,
                        onRow = { pointer, _ ->
                            if (phase == "row" && pointer == "/trading/matchingResults/0") Thread.currentThread().interrupt()
                        },
                        onScalar = { pointer, _ -> if (phase == "scalar" && pointer == "/tail") Thread.currentThread().interrupt() },
                        onStructure = { token ->
                            if (phase == "structure" && token.pointer == "" && token.token == JsonToken.END_OBJECT) {
                                Thread.currentThread().interrupt()
                            }
                        },
                    )
                }
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue(Files.isRegularFile(ref.file))
            }
        } finally {
            Thread.interrupted()
            if (previouslyInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `모든 금융 원소와 nested 주문을 한 행씩 보존하고 scalar와 구조는 별도로 남긴다`() {
        val original = fixture()
        val reference = publish(original)
        val rows = linkedMapOf<String, JsonNode>()
        val scalars = linkedMapOf<String, JsonNode>()
        val structure = mutableListOf<HttpPerformanceCheckpointToken>()
        val scan =
            HttpPerformanceCheckpointReader.scanRows(
                reference,
                onRow = { pointer, row -> check(rows.put(pointer, row) == null) },
                onScalar = { pointer, value -> check(scalars.put(pointer, value) == null) },
                onStructure = structure::add,
            )
        val full = mapper.readTree(mapper.writeValueAsBytes(original))
        val expectedPaths =
            setOf(
                "/applied/state/balances/0",
                "/applied/state/preparations/0",
                "/applied/state/pendingOrders/0",
                "/applied/state/requests/0",
                "/applied/state/transactions/0",
                "/trading/currentReservations/0",
                "/trading/completions/0",
                "/trading/matchingResults/0",
                "/trading/books/0/orders/0",
                "/trading/books/0/orders/1",
            )

        assertEquals(expectedPaths, rows.keys)
        for ((pointer, row) in rows) assertEquals(full.at(pointer), row, pointer)
        assertEquals(41L, scan.appliedPosition)
        assertEquals(JsonToken.START_OBJECT, scan.appliedStateToken)
        assertEquals(JsonToken.START_OBJECT, scan.tradingToken)
        assertEquals(2L, scan.bookOrderCount)
        assertEquals(1L, scan.rowCounts["/applied/state/requests"])
        assertEquals(1L, scan.rowCounts["/trading/books"])
        assertEquals(full.at("/trading/books/0/lastEngineSequence"), scalars["/trading/books/0/lastEngineSequence"])
        assertEquals(full.at("/applied/state/unrecognizedContext/a~1b~0c"), scalars["/applied/state/unrecognizedContext/a~1b~0c"])
        assertTrue(structure.any { it.pointer == "/trading/books/0/orders" && it.token == JsonToken.START_ARRAY })
        assertFalse(structure.any { it.pointer.startsWith("/trading/completions/0") })
        assertFalse(scalars.keys.any { it.startsWith("/applied/state/requests/0") })
        assertTrue(scan.tokens > rows.size)
    }

    @Test
    fun `row의 object 필드 순서는 tree 의미대로 무시되고 array 원소 순서는 보존된다`() {
        val document =
            """{"kind":"PERFORMANCE_CHECKPOINT","runId":"reader-test","memberId":0,"label":"final","applied":{"position":1,"state":{"requests":[{"b":[1,2],"a":2147483648},{"a":2147483648,"b":[2,1]}]}},"trading":{}}"""
        val rows = mutableListOf<JsonNode>()
        HttpPerformanceCheckpointReader.scanRows(raw(document), onRow = { _, row -> rows.add(row) })

        assertEquals(mapper.readTree("""{"a":2147483648,"b":[1,2]}"""), rows.first())
        assertFalse(rows.first() == rows.last())
        assertEquals(mapper.readTree("2147483648"), rows.first().path("a"))
    }

    @Test
    fun `null과 빈 배열은 missing으로 합치지 않고 원래 경계로 구별한다`() {
        val tokens = mutableListOf<HttpPerformanceCheckpointToken>()
        val ref =
            raw(
                """{"kind":"PERFORMANCE_CHECKPOINT","runId":"reader-test","memberId":0,"label":"final","applied":{"state":null},"trading":{"blocked":true,"matchingResults":[]}}""",
            )
        val scan = HttpPerformanceCheckpointReader.scanRows(ref, onRow = { _, _ -> error("빈 원문에는 행이 없습니다") }, onStructure = tokens::add)

        assertEquals(JsonToken.VALUE_NULL, scan.appliedStateToken)
        assertEquals(0L, scan.rowCounts["/trading/matchingResults"])
        assertFalse(scan.rowCounts.containsKey("/trading/completions"))
        assertTrue(tokens.any { it.pointer == "/applied/state" && it.token == JsonToken.VALUE_NULL })
        assertTrue(tokens.any { it.pointer == "/trading/matchingResults" && it.token == JsonToken.START_ARRAY })
        assertTrue(tokens.any { it.pointer == "/trading/blocked" && it.token == JsonToken.VALUE_TRUE })
    }

    @Test
    fun `중복 필드 잘린 문서와 뒤쪽 JSON 값은 모두 성공 반환하지 않는다`() {
        val valid = mapper.writeValueAsString(fixture())
        val documents =
            listOf(
                valid.dropLast(1),
                "$valid {}",
                valid.replace("\"memberId\":0", "\"memberId\":0,\"memberId\":0"),
                valid.replace("\"requestId\":\"M-0\"", "\"requestId\":\"M-0\",\"requestId\":\"M-0\""),
            )
        for (document in documents) {
            val ref = raw(document)
            assertFailsWith<Exception> { HttpPerformanceCheckpointReader.scan(ref) }
            assertEquals(document, Files.readString(ref.file), "실패 뒤 원문은 보존합니다")
        }
    }

    @Test
    fun `참조의 안전한 파일명과 run member label 크기를 검사한다`() {
        val ref = publish(fixture())
        val event = event(ref)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointReader.resolve(directory, event, "other-run", 0, "final") }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointReader.resolve(directory, event, "reader-test", 1, "final") }
        assertFailsWith<IllegalArgumentException> {
            HttpPerformanceCheckpointReader.resolve(
                directory,
                event,
                "reader-test",
                0,
                "other-label",
            )
        }
        val badName = event.deepCopy()
        (badName as ObjectNode).put("artifactFile", "../node0-checkpoint-final.json")
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointReader.resolve(directory, badName, "reader-test", 0, "final") }
        Files.writeString(ref.file, " ", StandardOpenOption.APPEND)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointReader.scan(ref) }
    }

    @Test
    fun `resolve 뒤 같은 길이 변조도 scan이 실제 읽은 SHA 검사로 거절한다`() {
        val ref = publish(fixture())
        val text = Files.readString(ref.file)
        val changed = text.replace("M-0", "M-9")
        assertFalse(text == changed)
        assertEquals(text.length, changed.length)
        Files.writeString(ref.file, changed)
        var observed = 0
        val failure = assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointReader.scan(ref) { observed++ } }

        assertTrue(observed > 0, "visitor 호출은 완전성 검사 전 잠정 자료입니다")
        assertTrue(failure.message.orEmpty().contains("SHA-256"))
        assertEquals(changed, Files.readString(ref.file))
    }

    @Test
    fun `정상 JSON이어도 본문 식별자 누락과 다른 member는 거절한다`() {
        val valid = mapper.writeValueAsString(fixture())
        for (document in listOf(valid.replace("\"memberId\":0", "\"memberId\":1"), valid.replace("\"runId\":\"reader-test\",", ""))) {
            assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointReader.scan(raw(document)) }
        }
    }

    @Test
    fun `지원 상한을 넘는 단일 row와 scalar는 잘라내지 않고 실패한다`() {
        val ref = publish(fixture())
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointReader.scanRows(ref, onRow = { _, _ -> }, maxRowNodes = 1) }
        assertFailsWith<IllegalArgumentException> {
            HttpPerformanceCheckpointReader.scanRows(ref, onRow = {
                _,
                _,
                ->
            }, maxRowTextChars = 1)
        }
        val scalar = "x".repeat(65_537)
        val document = mapper.writeValueAsString(fixture() + ("extra" to scalar))
        assertFailsWith<Exception> { HttpPerformanceCheckpointReader.scan(raw(document)) }
    }

    @Test
    fun `consumer 예외는 같은 객체로 전파하고 원문은 계속 보존한다`() {
        val ref = publish(fixture())
        val failure = IllegalStateException("deliberate row consumer failure")
        val actual =
            assertFailsWith<IllegalStateException> {
                HttpPerformanceCheckpointReader.scanRows(ref, onRow = {
                    _,
                    _,
                    ->
                    throw failure
                })
            }

        assertSame(failure, actual)
        assertTrue(Files.isRegularFile(ref.file))
        assertEquals(ref.bytes, Files.size(ref.file))
    }

    @Test
    fun `원소 선택은 미선택 원소를 구조로 다시 내보내지 않고 전체 배열과 파일은 검사한다`() {
        val ref = publish(fixture())
        val paths = mutableListOf<String>()
        val structure = mutableListOf<HttpPerformanceCheckpointToken>()
        val scan =
            HttpPerformanceCheckpointReader.scanRows(
                ref,
                onRow = { pointer, _ -> paths.add(pointer) },
                onStructure = structure::add,
                includeRow = { it.startsWith("/applied/state/requests/") },
            )

        assertEquals(listOf("/applied/state/requests/0"), paths)
        assertEquals(1L, scan.rowCounts["/trading/completions"])
        assertEquals(2L, scan.bookOrderCount)
        assertFalse(structure.any { it.pointer.startsWith("/trading/completions/0") })
        assertFalse(structure.any { it.pointer.startsWith("/trading/books/0/orders/0") })
        assertTrue(structure.any { it.pointer == "/trading/completions" && it.token == JsonToken.END_ARRAY })
    }

    @Test
    fun `scan 중 파일 뒤에 붙은 bytes도 같은 열린 stream의 실독 bytes로 거절한다`() {
        val ref = publish(fixture())
        var appended = false
        val failure =
            assertFailsWith<IllegalArgumentException> {
                HttpPerformanceCheckpointReader.scan(ref) { event ->
                    if (!appended && event.pointer == "/trading") {
                        Files.writeString(ref.file, " ", StandardOpenOption.APPEND)
                        appended = true
                    }
                }
            }

        assertTrue(appended)
        assertTrue(failure.message.orEmpty().contains("bytes"))
        assertEquals(ref.bytes + 1, Files.size(ref.file))
    }

    @Test
    fun `취소 interrupt는 성공으로 바꾸지 않고 원래 thread flag를 유지한다`() {
        val ref = publish(fixture())
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { HttpPerformanceCheckpointReader.scan(ref) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertTrue(Files.isRegularFile(ref.file))
    }

    @Test
    fun `sentMarker row는 opt-in일 때만 전달하고 금융 기본 callback 범위는 유지한다`() {
        val value =
            fixture() + ("markerSummary" to mapOf("sentMarkers" to listOf(mapOf("requestId" to "M-0"), mapOf("requestId" to "M-1"))))
        val ref = publish(value)
        val defaultRows = mutableListOf<String>()
        HttpPerformanceCheckpointReader.scanRows(ref, onRow = { pointer, _ -> defaultRows.add(pointer) })
        assertFalse(defaultRows.any { it.startsWith("/markerSummary/") })
        val markers = mutableListOf<JsonNode>()
        val structure = mutableListOf<HttpPerformanceCheckpointToken>()
        val scan =
            HttpPerformanceCheckpointReader.scanRows(
                ref,
                onRow = { _, row -> markers.add(row) },
                onStructure = structure::add,
                includeRow = { it.startsWith("/markerSummary/sentMarkers/") },
                includeMarkerRows = true,
            )
        assertEquals(listOf("M-0", "M-1"), markers.map { it.path("requestId").asString() })
        assertEquals(2L, scan.rowCounts["/markerSummary/sentMarkers"])
        assertFalse(structure.any { it.pointer.startsWith("/markerSummary/sentMarkers/0") })
    }

    @Test
    fun `marker node를 만들지 않는 metadata pass도 실제 원문 배열 count를 검증한다`() {
        val ref = publish(fixture() + ("markerSummary" to mapOf("sentMarkers" to emptyList<Any>())))
        val scan =
            HttpPerformanceCheckpointReader.scanRows(ref, onRow = {
                _,
                _,
                ->
                error("metadata pass")
            }, includeRow = { false }, includeMarkerRows = true)
        assertEquals(0L, scan.rowCounts["/markerSummary/sentMarkers"])
    }

    private fun fixture(): Map<String, Any?> =
        linkedMapOf(
            "kind" to "PERFORMANCE_CHECKPOINT",
            "runId" to "reader-test",
            "memberId" to 0,
            "label" to "final",
            "applied" to
                mapOf(
                    "position" to 41L,
                    "state" to
                        linkedMapOf(
                            "balances" to listOf(mapOf("userId" to "BUYER", "available" to 2_147_483_648L)),
                            "preparations" to listOf(mapOf("preparationId" to "P-0")),
                            "pendingOrders" to listOf(mapOf("sequence" to 3, "candidate" to mapOf("note" to "pending"))),
                            "requests" to listOf(mapOf("requestId" to "M-0", "order" to mapOf("quantity" to 100_000_000))),
                            "transactions" to listOf(mapOf("transactionId" to "T-0", "postings" to listOf(mapOf("amount" to 10)))),
                            "unrecognizedContext" to mapOf("a/b~c" to "preserved"),
                        ),
                ),
            "trading" to
                mapOf(
                    "currentReservations" to listOf(mapOf("orderId" to "M-0", "status" to "ACTIVE")),
                    "books" to
                        listOf(
                            mapOf(
                                "marketId" to "BTC-USDT",
                                "lastEngineSequence" to 7,
                                "orders" to listOf(mapOf("orderId" to "S-0"), mapOf("orderId" to "S-1")),
                            ),
                        ),
                    "completions" to
                        listOf(
                            mapOf(
                                "executionId" to "E-0",
                                "events" to listOf(mapOf("kind" to "TRADE_EXECUTED")),
                                "settlements" to emptyList<Any>(),
                            ),
                        ),
                    "matchingResultCount" to 1,
                    "matchingResults" to listOf(mapOf("commandId" to "E-0", "events" to emptyList<Any>())),
                ),
        )

    private fun publish(value: Map<String, Any?>): HttpPerformanceCheckpointFileReference {
        val published = HttpPerformanceCheckpointFiles.publish(directory, value)
        return HttpPerformanceCheckpointReader.resolve(directory, mapper.valueToTree(published), "reader-test", 0, "final")
    }

    private fun raw(document: String): HttpPerformanceCheckpointFileReference {
        val file = directory.resolve("node0-checkpoint-final.json")
        Files.writeString(file, document)
        return HttpPerformanceCheckpointFileReference(file, "reader-test", 0, "final", Files.size(file), sha256(file))
    }

    private fun event(ref: HttpPerformanceCheckpointFileReference): JsonNode =
        mapper.valueToTree(
            mapOf(
                "kind" to "PERFORMANCE_CHECKPOINT",
                "runId" to ref.runId,
                "memberId" to ref.memberId,
                "label" to ref.label,
                "artifactFile" to ref.file.fileName.toString(),
                "artifactBytes" to ref.bytes,
                "artifactSha256" to ref.sha256,
            ),
        )

    private fun sha256(path: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
}
