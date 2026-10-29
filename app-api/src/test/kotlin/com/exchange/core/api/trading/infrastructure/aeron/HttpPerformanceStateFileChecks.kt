package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Parent-side, test-source replacement for the full-tree checker. Requires the independent
 * HttpPerformanceCheckpointReader proposal. Every pass checks original bytes/SHA, full EOF,
 * duplicate fields, parser bounds, file identity and actual array counts before succeeding.
 *
 * Three complete row-bounded parses precede exact raw-byte comparison of applied.state and trading.
 * Different raw representations fall back to the original bounded skeleton and semantic row
 * comparison, preserving object-field-order independence and array ordering. Every byte-comparison
 * source is read through EOF and rechecked against its own saved SHA on those same reads.
 */
internal fun checkHttpPerformanceStateFiles(
    checkpoints: Map<Int, HttpPerformanceCheckpointFileReference>,
    warmup: Int,
    orders: Int,
    maxOrders: Int = 120_000,
): Map<String, Any> {
    val bounds = HttpPerformanceFinancialBounds(warmup, orders, maximumOrders = maxOrders)
    check(checkpoints.keys == setOf(0, 1, 2)) { "세 노드 사후 상태가 모두 필요합니다" }
    val proofDirectory =
        checkpoints
            .getValue(0)
            .file
            .toAbsolutePath()
            .parent
    val proofs = HttpPerformanceCompletionProofStore.disk(proofDirectory, bounds.admissions, bounds.maximumEventProofBytes)
    // use preserves the original comparison/read/financial failure and suppresses flush/close errors.
    return proofs.use {
        val financial = HttpPerformanceStreamingFinancialChecks(bounds, proofs)
        val copies =
            compareCheckpointCopiesFiles(
                checkpoints,
                includeFirstRows = true,
                onFirstRow = financial::row,
                onFirstScalar = financial::scalar,
            )
        // Completion proofs are fully loaded and node 0 has passed EOF/SHA before matching is consumed.
        // This pass occurs exactly once on both comparison paths, regardless of JSON field ordering.
        HttpPerformanceCheckpointReader.scanRows(
            checkpoints.getValue(0),
            onRow = { _, row -> financial.matching(row) },
            includeRow = { pointer -> pointer.startsWith("/trading/matchingResults/") },
        )
        financial.finish(copies.appliedPosition)
    }
}

/** Small verified parse metadata; no checkpoint/row trees or complete state projection. */
internal data class HttpPerformanceCheckpointCopiesFileCheck(
    val appliedPosition: Long,
    val scans: Map<Int, HttpPerformanceCheckpointScan>,
)

/** Also usable after SELL preparation: validates copies without applying final BUY financial formulas. */
internal fun checkHttpPerformanceCheckpointCopiesFiles(
    checkpoints: Map<Int, HttpPerformanceCheckpointFileReference>,
): HttpPerformanceCheckpointCopiesFileCheck = compareCheckpointCopiesFiles(checkpoints)

private fun compareCheckpointCopiesFiles(
    checkpoints: Map<Int, HttpPerformanceCheckpointFileReference>,
    includeFirstRows: Boolean = false,
    onFirstRow: (String, JsonNode) -> Unit = { _, _ -> },
    onFirstScalar: (String, JsonNode) -> Unit = { _, _ -> },
): HttpPerformanceCheckpointCopiesFileCheck {
    check(checkpoints.keys == setOf(0, 1, 2)) { "세 노드 사후 상태가 모두 필요합니다" }
    val references = (0..2).map { checkNotNull(checkpoints[it]).also { reference -> check(reference.memberId == it) } }
    check(references.map { it.runId }.distinct().size == 1 && references.map { it.label }.distinct().size == 1)
    val structures =
        references.mapIndexed { index, reference ->
            val skeleton = CheckpointStateSkeleton()
            val scan =
                HttpPerformanceCheckpointReader.scanRows(
                    reference,
                    onRow = { pointer, row -> if (includeFirstRows && index == 0) onFirstRow(pointer, row) },
                    onScalar = { pointer, value ->
                        skeleton.scalar(pointer, value)
                        if (index == 0) onFirstScalar(pointer, value)
                    },
                    onStructure = skeleton::structure,
                    // Fast success must retain the original row caps on ALL three files.
                    // Each row tree is discarded immediately; only node 0 feeds financial checks.
                    includeRow = ::statePointer,
                )
            check(scan.appliedStateToken == JsonToken.START_OBJECT && scan.tradingToken == JsonToken.START_OBJECT) {
                "적용 상태가 null이거나 업무 상태가 object가 아닙니다"
            }
            checkNotNull(scan.appliedPosition) { "적용 위치 누락" }
            skeleton.requireKnownArrays()
            scan to skeleton
        }
    val position = checkNotNull(structures.first().first.appliedPosition)
    check(structures.all { it.first.appliedPosition == position }) { "세 노드 적용 위치가 다릅니다" }
    check(structures.all { it.second == structures.first().second }) { "세 노드의 전체 업무 상태 scalar/구조가 다릅니다" }
    if (!compareHttpPerformanceCheckpointBusinessBytes(structures.map { it.first })) {
        for (section in httpPerformanceFinancialRowSections) {
            compareCheckpointSection(references, section) { _, _ -> }
        }
    }
    // Every original and fallback comparison has passed EOF/SHA; the financial caller must still
    // complete its separate matching pass before reporting a financial verdict.
    return HttpPerformanceCheckpointCopiesFileCheck(position, structures.mapIndexed { index, pair -> index to pair.first }.toMap())
}

internal val httpPerformanceFinancialRowSections =
    listOf(
        "/applied/state/balances",
        "/applied/state/preparations",
        "/applied/state/pendingOrders",
        "/applied/state/requests",
        "/applied/state/transactions",
        "/trading/currentReservations",
        "/trading/completions",
        "/trading/matchingResults",
        "/trading/books/*/orders",
    )

private val nestedBookOrder = Regex("/trading/books/[0-9]+/orders/[0-9]+")
private val nestedBookOrders = Regex("/trading/books/[0-9]+/orders")

private fun statePointer(pointer: String): Boolean =
    pointer == "/applied/state" || pointer.startsWith("/applied/state/") || pointer == "/trading" || pointer.startsWith("/trading/")

private fun rowSection(pointer: String): String =
    if (nestedBookOrder.matches(pointer)) "/trading/books/*/orders" else pointer.substringBeforeLast('/')

/** Fixed-workload metadata is small. Unknown large metadata fails explicitly instead of dropping it. */
private data class CheckpointStateSkeleton(
    val containers: MutableMap<String, JsonToken> = HashMap(),
    val values: MutableMap<String, JsonNode> = HashMap(),
) {
    fun structure(event: HttpPerformanceCheckpointToken) {
        if (!statePointer(event.pointer)) return
        if (event.token == JsonToken.START_OBJECT || event.token == JsonToken.START_ARRAY) {
            check(containers.size + values.size < MAX_ENTRIES) { "체크포인트 scalar/구조 metadata 상한 초과" }
            check(containers.put(event.pointer, event.token) == null) { "체크포인트 구조 경로 중복" }
        }
    }

    fun scalar(
        pointer: String,
        value: JsonNode,
    ) {
        if (!statePointer(pointer)) return
        check(containers.size + values.size < MAX_ENTRIES) { "체크포인트 scalar/구조 metadata 상한 초과" }
        check(values.put(pointer, value) == null) { "체크포인트 scalar 경로 중복" }
    }

    fun requireKnownArrays() {
        for (section in httpPerformanceFinancialRowSections.filterNot { '*' in it } + "/trading/books") {
            check(containers[section] == JsonToken.START_ARRAY) { "필수 업무 배열 누락: $section" }
        }
        for ((path, token) in containers) if (nestedBookOrders.matches(path)) check(token == JsonToken.START_ARRAY)
        check(values["/trading/blocked"]?.asBoolean() != true) { "차단된 업무 상태" }
    }

    companion object {
        private const val MAX_ENTRIES = 4_096
    }
}

/**
 * One bounded batch is offered per reader, with acknowledgement before the next selected row.
 * The exact row comparison has no fingerprint, hash, count/XOR substitute or sampled fields.
 * EOF/SHA must succeed in all three readers before End can complete a section.
 */
internal fun compareCheckpointSection(
    references: List<HttpPerformanceCheckpointFileReference>,
    section: String,
    compared: (String, JsonNode) -> Unit,
) {
    val cancelled = AtomicBoolean(false)
    val cursors = ArrayList<ReferenceRowCursor>(3)
    var primaryFailure: Throwable? = null
    try {
        for (reference in references) cursors.add(ReferenceRowCursor(reference, section, cancelled))
        while (true) {
            requireCheckpointComparisonActive()
            val messages = ArrayList<RowMessage>(3)
            try {
                for (cursor in cursors) messages.add(cursor.next())
                for (message in messages) {
                    if (message is RowMessage.Failure) {
                        throw IllegalStateException(
                            "체크포인트 section 읽기 실패: $section",
                            message.error,
                        )
                    }
                }
                if (messages.all { it is RowMessage.End }) {
                    requireCheckpointComparisonActive()
                    return
                }
                check(messages.all { it is RowMessage.Batch }) { "세 노드의 전체 업무 배열 크기가 다릅니다: $section" }
                val first = messages.first() as RowMessage.Batch
                val others = messages.drop(1).map { it as RowMessage.Batch }
                check(others.all { it.rows.size == first.rows.size }) { "세 노드의 전체 업무 배열 크기가 다릅니다: $section" }
                for (index in first.rows.indices) {
                    requireCheckpointComparisonActive()
                    val row = first.rows[index]
                    for (other in others) {
                        val candidate = other.rows[index]
                        check(row.pointer == candidate.pointer && row.value == candidate.value) { "세 노드의 전체 업무 상태가 다릅니다: ${row.pointer}" }
                    }
                    compared(row.pointer, row.value)
                    requireCheckpointComparisonActive()
                }
            } finally {
                for (message in messages) if (message is RowMessage.Batch) message.consumed.countDown()
            }
        }
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        cancelled.set(true)
        var cleanupFailure: Throwable? = null
        for (cursor in cursors) {
            try {
                cursor.close()
            } catch (error: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
            }
        }
        cleanupFailure?.let { error ->
            val original = primaryFailure
            if (original == null) throw error else original.addSuppressed(error)
        }
    }
}

private fun requireCheckpointComparisonActive() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("체크포인트 section 비교 interrupt")
}

internal const val HTTP_PERFORMANCE_CHECKPOINT_ROW_BATCH_SIZE = 32

internal data class CheckpointComparedRow(
    val pointer: String,
    val value: JsonNode,
)

internal sealed interface RowMessage {
    data class Batch(
        val rows: List<CheckpointComparedRow>,
        val consumed: CountDownLatch = CountDownLatch(1),
    ) : RowMessage {
        init {
            require(rows.size in 1..HTTP_PERFORMANCE_CHECKPOINT_ROW_BATCH_SIZE)
        }
    }

    data class End(
        val scan: HttpPerformanceCheckpointScan,
    ) : RowMessage

    data class Failure(
        val error: Throwable,
    ) : RowMessage
}

/** Test-source cursor only. Queue capacity stays one; acknowledgement is per at most 32 exact rows. */
internal class ReferenceRowCursor(
    reference: HttpPerformanceCheckpointFileReference,
    section: String,
    private val cancelled: AtomicBoolean,
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<RowMessage>(1)

    @Volatile
    private var terminal: RowMessage? = null

    private val worker =
        Thread({
            val pending = ArrayList<CheckpointComparedRow>(HTTP_PERFORMANCE_CHECKPOINT_ROW_BATCH_SIZE)

            fun flush() {
                if (pending.isEmpty()) return
                ensureActive()
                val message = RowMessage.Batch(pending.toList())
                while (!queue.offer(message, 100, TimeUnit.MILLISECONDS)) ensureActive()
                while (!message.consumed.await(100, TimeUnit.MILLISECONDS)) ensureActive()
                ensureActive()
                pending.clear()
            }

            try {
                val scan =
                    HttpPerformanceCheckpointReader.scanRows(
                        reference,
                        onRow = { pointer, row ->
                            ensureActive()
                            if (rowSection(pointer) == section) {
                                pending.add(CheckpointComparedRow(pointer, row))
                                if (pending.size == HTTP_PERFORMANCE_CHECKPOINT_ROW_BATCH_SIZE) flush()
                            }
                        },
                        onStructure = { ensureActive() },
                        includeRow = { pointer -> rowSection(pointer) == section },
                    )
                ensureActive()
                // A partial final batch is published only after the complete original passed EOF/SHA.
                flush()
                ensureActive()
                finish(RowMessage.End(scan))
            } catch (error: Throwable) {
                // Do not lose the original failure trying to enqueue while this worker is interrupted.
                finish(RowMessage.Failure(error))
            }
        }, "checkpoint-${reference.memberId}-${section.substringAfterLast('/')}").apply {
            isDaemon = true
            start()
        }

    fun next(): RowMessage {
        val started = System.nanoTime()
        while (true) {
            requireCheckpointComparisonActive()
            if (cancelled.get()) throw CancellationException("체크포인트 section 비교 종료")
            queue.poll()?.let { return it }
            terminal?.let { return it }
            val message =
                try {
                    queue.poll(100, TimeUnit.MILLISECONDS)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                }
            if (message != null) return message
            terminal?.let { return it }
            check(System.nanoTime() - started < TimeUnit.MINUTES.toNanos(5)) { "체크포인트 section cursor 시간 초과" }
        }
    }

    override fun close() {
        worker.interrupt()
        var interrupted = Thread.interrupted()
        val started = System.nanoTime()
        try {
            while (worker.isAlive) {
                val remaining = TimeUnit.SECONDS.toNanos(1) - (System.nanoTime() - started)
                if (remaining <= 0) break
                try {
                    worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            check(!worker.isAlive) { "체크포인트 section reader 종료 미완료: ${worker.name}" }
            if (interrupted) throw InterruptedException("체크포인트 section reader 정리 중 interrupt")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun finish(message: RowMessage) {
        terminal = message
        // Wake a waiting consumer without a blocking or interruptible failure-publication path.
        queue.offer(message)
    }

    private fun ensureActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("체크포인트 section 비교 종료")
    }
}
