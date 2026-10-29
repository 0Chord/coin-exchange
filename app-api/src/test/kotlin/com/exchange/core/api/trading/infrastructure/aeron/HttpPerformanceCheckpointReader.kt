package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Test-source file identity only: no checkpoint tree, projected state, or financial verdict is retained. */
internal data class HttpPerformanceCheckpointFileReference(
    val file: Path,
    val runId: String,
    val memberId: Int,
    val label: String,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(file.isAbsolute && memberId in 0..2 && label.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        require(file.fileName.toString() == "node$memberId-checkpoint-$label.json")
        require(bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** JSON pointer includes every object name and array index. Numeric text has not been semantically normalized. */
internal data class HttpPerformanceCheckpointToken(
    val pointer: String,
    val token: JsonToken,
    val text: String? = null,
)

/** Integrity result, not a three-copy or financial verification result. Counts come from actual arrays. */
internal data class HttpPerformanceCheckpointScan(
    val reference: HttpPerformanceCheckpointFileReference,
    val appliedPosition: Long?,
    val appliedStateToken: JsonToken?,
    val tradingToken: JsonToken?,
    val rowCounts: Map<String, Long>,
    val bookOrderCount: Long,
    val tokens: Long,
    /** Populated only from supported byte locations, and provisional until scan returns. */
    val businessByteSpans: Map<String, HttpPerformanceCheckpointByteSpan> = emptyMap(),
)

/**
 * Optional parent-reader foundation. Not connected to Cluster or the financial checker yet.
 * Visits the complete original document without whole-file readTree, skipChildren, row sampling, or retained value lists.
 * Visitor events are provisional until scan returns: EOF, bytes, SHA and header identity are checked last.
 */
internal object HttpPerformanceCheckpointReader {
    private val factory =
        JsonFactory
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints
                    .builder()
                    .maxNestingDepth(64)
                    .maxStringLength(65_536)
                    .maxNameLength(256)
                    .maxNumberLength(128)
                    .build(),
            ).build()
    private val mapper = JsonMapper.builder(factory).build()
    private val countedArrays =
        setOf(
            "/applied/state/balances",
            "/applied/state/preparations",
            "/applied/state/pendingOrders",
            "/applied/state/requests",
            "/applied/state/transactions",
            "/trading/currentReservations",
            "/trading/books",
            "/trading/completions",
            "/trading/matchingResults",
            "/markerSummary/sentMarkers",
        )
    private val bookOrders = Regex("/trading/books/[0-9]+/orders")
    private val rowRoot =
        Regex(
            "/(?:applied/state/(?:balances|preparations|pendingOrders|requests|transactions)|" +
                "trading/(?:currentReservations|completions|matchingResults))/[0-9]+|" +
                "/trading/books/[0-9]+/orders/[0-9]+",
        )

    private val markerRowRoot = Regex("/markerSummary/sentMarkers/[0-9]+")

    fun resolve(
        directory: Path,
        event: JsonNode,
        runId: String,
        memberId: Int,
        label: String,
    ): HttpPerformanceCheckpointFileReference {
        require(memberId in 0..2 && label.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        require(event.path("kind").asString() == "PERFORMANCE_CHECKPOINT")
        require(event.path("runId").asString() == runId && httpPerformanceCheckpointMemberMatches(event.path("memberId"), memberId))
        require(event.path("label").asString() == label)
        val name = "node$memberId-checkpoint-$label.json"
        require(event.path("artifactFile").asString() == name) { "체크포인트 파일 식별자가 다릅니다" }
        val reference =
            HttpPerformanceCheckpointFileReference(
                directory.toAbsolutePath().resolve(name),
                runId,
                memberId,
                label,
                event.path("artifactBytes").asLong(),
                event.path("artifactSha256").asString(),
            )
        require(Files.isRegularFile(reference.file) && Files.size(reference.file) == reference.bytes) { "체크포인트 파일 크기가 다릅니다" }
        return reference
    }

    fun scan(
        reference: HttpPerformanceCheckpointFileReference,
        visit: (HttpPerformanceCheckpointToken) -> Unit = {},
    ): HttpPerformanceCheckpointScan {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("checkpoint parse interrupted before open")
        require(Files.isRegularFile(reference.file) && Files.size(reference.file) == reference.bytes) { "체크포인트 파일 크기가 다릅니다" }
        val identity = mutableMapOf<String, HttpPerformanceCheckpointToken>()
        val rowCounts = linkedMapOf<String, Long>()
        val businessByteSpans = linkedMapOf<String, HttpPerformanceCheckpointByteSpan>()
        var position: Long? = null
        var stateToken: JsonToken? = null
        var tradingToken: JsonToken? = null
        var orders = 0L
        var tokens = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(reference.file).use { source ->
            val input = CountingDigestInput(source, digest)
            mapper.createParser(input).use { parser ->
                require(parser.nextToken() == JsonToken.START_OBJECT) { "체크포인트 root는 object여야 합니다" }
                walk(
                    parser,
                    "",
                    visit = { event ->
                        tokens = Math.incrementExact(tokens)
                        when (event.pointer) {
                            "/kind", "/runId", "/memberId", "/label" -> {
                                require(event.token.isScalarValue) { "체크포인트 식별자는 scalar여야 합니다: ${event.pointer}" }
                                check(identity.put(event.pointer, event) == null) { "체크포인트 식별자 중복: ${event.pointer}" }
                            }

                            "/applied/position" -> {
                                require(event.token == JsonToken.VALUE_NUMBER_INT) { "체크포인트 적용 위치는 정수여야 합니다" }
                                position = requireNotNull(event.text?.toLongOrNull()) { "체크포인트 적용 위치 Long 범위 초과" }
                            }

                            "/applied/state" -> {
                                if (event.token != JsonToken.END_OBJECT &&
                                    event.token != JsonToken.END_ARRAY
                                ) {
                                    stateToken = event.token
                                }
                            }

                            "/trading" -> {
                                if (event.token != JsonToken.END_OBJECT &&
                                    event.token != JsonToken.END_ARRAY
                                ) {
                                    tradingToken = event.token
                                }
                            }
                        }
                        visit(event)
                        checkReaderInterruption(event.pointer)
                    },
                    objectByteSpan = { pointer, start, closing, after ->
                        if (!(start == -1L && closing == -1L && after == -1L)) {
                            check(start >= 0 && closing > start && after == Math.incrementExact(closing) && after <= reference.bytes) {
                                "체크포인트 업무 JSON byte 경계가 잘못되었습니다: $pointer"
                            }
                            check(businessByteSpans.put(pointer, HttpPerformanceCheckpointByteSpan(start, after)) == null)
                        }
                    },
                    arrayEnd = { pointer, count ->
                        if (pointer in countedArrays) rowCounts[pointer] = count
                        if (bookOrders.matches(pointer)) orders = Math.addExact(orders, count)
                    },
                )
                require(parser.nextToken() == null) { "체크포인트 뒤에 추가 JSON 값이 있습니다" }
                checkReaderInterruption("EOF")
            }
            require(input.bytes == reference.bytes) { "읽은 체크포인트 bytes가 다릅니다" }
            require(HexFormat.of().formatHex(digest.digest()) == reference.sha256) { "읽은 체크포인트 SHA-256이 다릅니다" }
        }
        for ((pointer, expected) in mapOf(
            "/kind" to "PERFORMANCE_CHECKPOINT",
            "/runId" to reference.runId,
            "/label" to reference.label,
        )) {
            val actual = identity[pointer]
            require(actual != null && actual.token == JsonToken.VALUE_STRING && actual.text == expected) { "체크포인트 내용 식별자가 다릅니다: $pointer" }
        }
        val member = identity["/memberId"]
        require(member != null && member.token == JsonToken.VALUE_NUMBER_INT && member.text?.toIntOrNull() == reference.memberId) {
            "체크포인트 내용 memberId가 다릅니다"
        }
        checkReaderInterruption("complete")
        return HttpPerformanceCheckpointScan(
            reference,
            position,
            stateToken,
            tradingToken,
            rowCounts.toMap(),
            orders,
            tokens,
            businessByteSpans.toMap(),
        )
    }

    private fun checkReaderInterruption(pointer: String) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("checkpoint parse interrupted at $pointer")
    }

    /**
     * Supplies one complete financial row at a time; nested book orders never form a whole book tree.
     * onStructure/onScalar exclude row roots and row contents. They include all other fields, even unknown ones.
     * Every callback is provisional until the returned scan has passed EOF, bytes, SHA and identity checks.
     * Consumers must not retain all rows or report verification success from a callback.
     * includeRow only suppresses row-node allocation/callbacks; the full original stream is still parsed and checked.
     * includeMarkerRows opts into sent-marker rows; the default financial row callback scope is unchanged.
     */
    fun scanRows(
        reference: HttpPerformanceCheckpointFileReference,
        onRow: (String, JsonNode) -> Unit,
        onScalar: (String, JsonNode) -> Unit = { _, _ -> },
        onStructure: (HttpPerformanceCheckpointToken) -> Unit = {},
        maxRowNodes: Int = 16_384,
        maxRowTextChars: Int = 1_048_576,
        includeRow: (String) -> Boolean = { true },
        includeMarkerRows: Boolean = false,
    ): HttpPerformanceCheckpointScan {
        require(maxRowNodes in 1..16_384 && maxRowTextChars in 1..1_048_576)
        var row: RowTree? = null
        var excludedRow: String? = null
        return scan(reference) { event ->
            if (excludedRow != null) {
                if (event.pointer == excludedRow &&
                    (event.token == JsonToken.END_OBJECT || event.token == JsonToken.END_ARRAY)
                ) {
                    excludedRow = null
                }
                return@scan
            }
            if (row == null && (rowRoot.matches(event.pointer) || (includeMarkerRows && markerRowRoot.matches(event.pointer)))) {
                if (!includeRow(event.pointer)) {
                    if (!event.token.isScalarValue) excludedRow = event.pointer
                    return@scan
                }
                row = RowTree(event.pointer, maxRowNodes, maxRowTextChars)
            }
            val current = row
            if (current != null) {
                current.accept(event)?.let { completed ->
                    onRow(current.pointer, completed)
                    row = null
                }
            } else {
                onStructure(event)
                if (event.token.isScalarValue) onScalar(event.pointer, scalarNode(event))
            }
        }
    }

    private fun scalarNode(event: HttpPerformanceCheckpointToken): JsonNode =
        when (event.token) {
            JsonToken.VALUE_STRING -> mapper.valueToTree<JsonNode>(checkNotNull(event.text))
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> mapper.readTree(checkNotNull(event.text))
            JsonToken.VALUE_TRUE -> mapper.booleanNode(true)
            JsonToken.VALUE_FALSE -> mapper.booleanNode(false)
            JsonToken.VALUE_NULL -> mapper.nullNode()
            else -> error("scalar가 아닌 checkpoint token: ${event.token}")
        }

    /** A row cap is an explicit unsupported-input failure, never sampling or truncating a record. */
    private class RowTree(
        val pointer: String,
        private val maxNodes: Int,
        private val maxTextChars: Int,
    ) {
        private val containers = mutableListOf<JsonNode>()
        private var root: JsonNode? = null
        private var nodes = 0
        private var textChars = 0

        fun accept(event: HttpPerformanceCheckpointToken): JsonNode? {
            if (event.token == JsonToken.END_OBJECT || event.token == JsonToken.END_ARRAY) {
                check(containers.isNotEmpty()) { "checkpoint row 닫기 불일치: ${event.pointer}" }
                containers.removeAt(containers.lastIndex)
                return if (containers.isEmpty()) checkNotNull(root) else null
            }
            nodes = Math.incrementExact(nodes)
            require(nodes <= maxNodes) { "checkpoint row node 상한 초과: $pointer" }
            textChars = Math.addExact(textChars, event.text?.length ?: 0)
            require(textChars <= maxTextChars) { "checkpoint row text 상한 초과: $pointer" }
            val value =
                when (event.token) {
                    JsonToken.START_OBJECT -> mapper.createObjectNode()
                    JsonToken.START_ARRAY -> mapper.createArrayNode()
                    else -> scalarNode(event)
                }
            when (val parent = containers.lastOrNull()) {
                null -> {
                    check(root == null) { "checkpoint row root 중복: $pointer" }
                    root = value
                }

                is ObjectNode -> {
                    val name =
                        event.pointer
                            .substringAfterLast('/')
                            .replace("~1", "/")
                            .replace("~0", "~")
                    parent.set(name, value)
                }

                is ArrayNode -> {
                    parent.add(value)
                }

                else -> {
                    error("checkpoint row parent 종류 오류: $pointer")
                }
            }
            return if (event.token == JsonToken.START_OBJECT || event.token == JsonToken.START_ARRAY) {
                containers.add(value)
                null
            } else if (containers.isEmpty()) {
                value
            } else {
                null
            }
        }
    }

    /** Keeps at most parser buffers, the current scalar/path, and at most 64 recursion frames. */
    private fun walk(
        parser: JsonParser,
        pointer: String,
        visit: (HttpPerformanceCheckpointToken) -> Unit,
        arrayEnd: (String, Long) -> Unit,
        objectByteSpan: (String, Long, Long, Long) -> Unit,
    ) {
        checkReaderInterruption(pointer)
        val token = checkNotNull(parser.currentToken()) { "체크포인트 값 누락: $pointer" }
        // Token byte locations are independent of parser read-ahead and UTF-8 character width.
        // Only the two complete object values are captured; no character-count arithmetic is used.
        val byteStart =
            if (token == JsonToken.START_OBJECT && (pointer == "/applied/state" || pointer == "/trading")) {
                parser.currentTokenLocation().byteOffset
            } else {
                null
            }
        val text = if (token.isScalarValue) parser.getString() else null
        visit(HttpPerformanceCheckpointToken(pointer, token, text))
        when (token) {
            JsonToken.START_OBJECT -> {
                var fields = 0
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    require(parser.currentToken() == JsonToken.PROPERTY_NAME) { "체크포인트 object가 잘렸습니다: $pointer" }
                    fields++
                    require(fields <= 256) { "체크포인트 object 필드 상한 초과: $pointer" }
                    val field = parser.currentName().replace("~", "~0").replace("/", "~1")
                    require(parser.nextToken() != null) { "체크포인트 필드 값이 잘렸습니다: $pointer/$field" }
                    walk(parser, "$pointer/$field", visit, arrayEnd, objectByteSpan)
                }
                visit(HttpPerformanceCheckpointToken(pointer, JsonToken.END_OBJECT))
                if (byteStart != null) {
                    objectByteSpan(pointer, byteStart, parser.currentTokenLocation().byteOffset, parser.currentLocation().byteOffset)
                }
            }

            JsonToken.START_ARRAY -> {
                var count = 0L
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    require(parser.currentToken() != null) { "체크포인트 배열이 잘렸습니다: $pointer" }
                    walk(parser, "$pointer/$count", visit, arrayEnd, objectByteSpan)
                    count = Math.incrementExact(count)
                }
                arrayEnd(pointer, count)
                visit(HttpPerformanceCheckpointToken(pointer, JsonToken.END_ARRAY))
            }

            JsonToken.VALUE_STRING, JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT,
            JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE, JsonToken.VALUE_NULL,
            -> {
                Unit
            }

            else -> {
                error("체크포인트 값으로 예상하지 않은 token: $token at $pointer")
            }
        }
    }

    private class CountingDigestInput(
        private val source: InputStream,
        private val digest: MessageDigest,
    ) : InputStream() {
        var bytes: Long = 0
            private set

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) {
                digest.update(value.toByte())
                bytes = Math.incrementExact(bytes)
            }
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count = source.read(buffer, offset, length)
            if (count > 0) {
                digest.update(buffer, offset, count)
                bytes = Math.addExact(bytes, count.toLong())
            }
            return count
        }

        override fun close() = source.close()
    }
}
