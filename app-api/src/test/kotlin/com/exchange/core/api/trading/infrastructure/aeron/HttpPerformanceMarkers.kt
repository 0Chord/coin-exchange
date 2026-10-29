package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.OneToOneConcurrentArrayQueue
import org.agrona.concurrent.SleepingMillisIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal const val HTTP_PERFORMANCE_MARKER_CAPACITY = 8192
private const val MAX_MARKER_PLAN_ORDERS = 600_000
private const val MAX_MARKER_PLAN_WARMUP = 12_000
private const val MAX_MARKER_PLAN_IDENTITIES = 2 * (MAX_MARKER_PLAN_ORDERS + MAX_MARKER_PLAN_WARMUP)
private const val MARKER_STREAM = 142
private const val MARKER_MAGIC = 0x48503134
private const val MAX_MARKER_BYTES = 2048

/** 고정 SELL(N+W), warmup BUY(W), measured BUY(N) 계획이다. 자원 적합성 보장이 아니다. */
internal data class HttpPerformanceMarkerPlan(
    val runId: String,
    val warmup: Int,
    val orders: Int,
) {
    init {
        require(runId.matches(Regex("[a-z0-9-]{1,64}")))
        require(warmup in 1..MAX_MARKER_PLAN_WARMUP)
        require(orders in 1..MAX_MARKER_PLAN_ORDERS)
    }

    val identitiesPerMember: Int = Math.multiplyExact(2, Math.addExact(warmup, orders))

    fun fields(): Map<String, Any> =
        mapOf(
            "runId" to runId,
            "warmup" to warmup,
            "orders" to orders,
            "workload" to "SELL(N+W), warmup BUY(W), measured BUY(N); retries reuse identities",
            "identityCapacityPerMember" to identitiesPerMember,
            "identityCapacityAllMembers" to Math.multiplyExact(3, identitiesPerMember),
            "senderPendingCapacity" to HTTP_PERFORMANCE_MARKER_CAPACITY,
            "senderPendingScope" to "queued plus currently sending; cumulative evidence is retained separately",
            "rawEvidence" to "full payloads and exact identity equality; no history reset or digest substitution",
        )
}

internal fun httpPerformanceMarkerPlanFromEnvironment(
    runId: String,
    environment: (String) -> String? = System::getenv,
): HttpPerformanceMarkerPlan =
    HttpPerformanceMarkerPlan(
        runId,
        requireNotNull(environment("PERFORMANCE_MARKER_PLAN_WARMUP")) { "완료 표식 warmup 계획이 없습니다" }.toInt(),
        requireNotNull(environment("PERFORMANCE_MARKER_PLAN_ORDERS")) { "완료 표식 orders 계획이 없습니다" }.toInt(),
    )

/** 단일 callback 생산자·송신 소비자의 순간 한도와 누적 원문을 분리한다. */
internal class HttpPerformanceMarkerOutbox(
    val capacity: Int,
    private val plan: HttpPerformanceMarkerPlan? = null,
    private val evidencePath: Path? = null,
    private val maximumEvidenceBytes: Long = 256L * 1024 * 1024,
) : AutoCloseable {
    internal enum class Status {
        QUEUED,
        SENDING,
        SENT,
        QUEUE_OVERFLOW,
        SEND_FAILED,
    }

    internal class Pending(
        val marker: HttpPerformanceCompletion,
        val enqueuedNs: Long?,
        var jfrEvent: HttpPerformanceJfrDeliveryEvent? = null,
        var ordinal: Int = -1,
    ) {
        @Volatile
        var status: Status = Status.QUEUED
    }

    val historyCapacity: Int = plan?.identitiesPerMember ?: HTTP_PERFORMANCE_MARKER_CAPACITY

    // Keep the memory-only mode for small unit tests and their original object identity contract.
    private val history = Collections.synchronizedList(mutableListOf<Pending>())
    private val active = LinkedHashMap<Int, Pending>()
    private val terminalEvidence = ArrayDeque<Pending>()
    private var historyCount = 0
    private var retainedEvidenceCount = 0
    private var firstEvidenceOverflowPending: Pending? = null
    private var firstEvidenceOverflow: HttpPerformanceCompletion? = null
    private val queue =
        run {
            require(capacity in 1..HTTP_PERFORMANCE_MARKER_CAPACITY && Integer.bitCount(capacity) == 1)
            require(maximumEvidenceBytes >= 8)
            require(evidencePath == null || !Files.exists(evidencePath)) { "기존 완료 표식 증거 경로는 재사용하지 않습니다: $evidencePath" }
            OneToOneConcurrentArrayQueue<Pending>(capacity)
        }
    private val pending = AtomicInteger()
    private val offered = AtomicLong()
    private val sent = AtomicLong()
    private val overflow = AtomicLong()
    private val historyOverflow = AtomicLong()
    private val evidenceOverflow = AtomicLong()

    @Volatile
    private var journal: HttpPerformanceMarkerJournal? = null

    @Volatile
    private var evidenceError: String? = null

    @Volatile
    private var firstHistoryOverflow: HttpPerformanceCompletion? = null

    fun offer(
        marker: HttpPerformanceCompletion,
        enqueuedNs: Long?,
        jfrEvent: HttpPerformanceJfrDeliveryEvent? = null,
    ) {
        require(plan == null || marker.runId == plan.runId) { "다른 실행의 완료 표식 송신" }
        offered.incrementAndGet()
        synchronized(history) {
            if (historyCount == historyCapacity) {
                if (historyOverflow.incrementAndGet() == 1L) firstHistoryOverflow = marker
                return
            }
            historyCount++
            val value = Pending(marker, enqueuedNs, jfrEvent)
            if (evidencePath == null) history.add(value)
            // Failed evidence stays hot, so it also prevents unbounded admission after a disk failure.
            if ((evidencePath != null && active.size == capacity) || !reservePending()) {
                queueOverflow(value)
            } else {
                if (!queue.offer(value)) {
                    pending.decrementAndGet()
                    queueOverflow(value)
                } else if (evidencePath != null) {
                    retain(value)
                    active[value.ordinal] = value
                }
            }
        }
    }

    private fun retain(value: Pending) {
        if (value.ordinal < 0) value.ordinal = retainedEvidenceCount++
    }

    /** The callback only enqueues terminal evidence; it never opens or writes a file. */
    private fun queueOverflow(value: Pending) {
        value.status = Status.QUEUE_OVERFLOW
        value.jfrEvent = null
        overflow.incrementAndGet()
        if (evidencePath == null) return
        if (terminalEvidence.size < capacity) {
            retain(value)
            terminalEvidence.addLast(value)
        } else {
            evidenceOverflow.incrementAndGet()
            if (firstEvidenceOverflow == null) {
                retain(value)
                firstEvidenceOverflow = value.marker
                firstEvidenceOverflowPending = value
            }
        }
    }

    private fun reservePending(): Boolean {
        while (true) {
            val current = pending.get()
            if (current == capacity) return false
            if (pending.compareAndSet(current, current + 1)) return true
        }
    }

    fun poll(): Pending? = synchronized(history) { queue.poll()?.also { it.status = Status.SENDING } }

    fun finish(
        value: Pending,
        delivered: Boolean,
    ) {
        synchronized(history) {
            check(value.status == Status.SENDING) { "송신 중인 완료 표식만 종료할 수 있습니다" }
            value.jfrEvent = null
            value.status = if (delivered) Status.SENT else Status.SEND_FAILED
            if (delivered) sent.incrementAndGet()
            check(pending.decrementAndGet() >= 0)
        }
        if (evidencePath != null) appendEvidence(value)
    }

    /** Only the existing sender invokes this during delivery and idle polls. */
    fun flushEvidence() {
        if (evidencePath == null) return
        while (true) {
            val value = synchronized(history) { terminalEvidence.firstOrNull() ?: firstEvidenceOverflowPending } ?: return
            if (!appendEvidence(value)) return
        }
    }

    private fun appendEvidence(value: Pending): Boolean {
        if (evidenceError != null) return false
        return try {
            val destination =
                journal ?: HttpPerformanceMarkerJournal(checkNotNull(evidencePath), historyCapacity, maximumEvidenceBytes).also {
                    synchronized(history) { journal = it }
                }
            val written = destination.append(record(value))
            synchronized(history) {
                destination.publish(written)
                active.remove(value.ordinal)
                if (terminalEvidence.firstOrNull() === value) terminalEvidence.removeFirst()
                if (firstEvidenceOverflowPending === value) firstEvidenceOverflowPending = null
            }
            true
        } catch (failure: Throwable) {
            evidenceError = "완료 표식 증거 저장 실패: ${failure.javaClass.simpleName}: ${failure.message}"
            false
        }
    }

    private fun record(value: Pending) = HttpPerformanceMarkerJournal.Record(value.ordinal, value.marker, value.status)

    val retainedPayloadCount: Int
        get() =
            synchronized(history) {
                if (evidencePath == null) {
                    history.size
                } else {
                    active.size + terminalEvidence.size + (if (firstEvidenceOverflow == null) 0 else 1) +
                        (if (firstHistoryOverflow == null) 0 else 1)
                }
            }

    val isEmpty: Boolean get() = queue.isEmpty()
    val isDrained: Boolean
        get() =
            synchronized(history) {
                offered.get() == sent.get() && pending.get() == 0 && overflow.get() == 0L && historyOverflow.get() == 0L &&
                    evidenceOverflow.get() == 0L && evidenceError == null &&
                    (evidencePath == null || (active.isEmpty() && terminalEvidence.isEmpty() && firstEvidenceOverflowPending == null))
            }

    fun summary(): Map<String, Any?> =
        synchronized(history) {
            val live =
                if (evidencePath == null) {
                    history.map(::record)
                } else {
                    (active.values + terminalEvidence + listOfNotNull(firstEvidenceOverflowPending)).map(::record).sortedBy { it.ordinal }
                }
            val records =
                journal?.snapshot(retainedEvidenceCount, sent.get().toInt(), live)
                    ?: (
                        live.filter { it.status == Status.SENT }.map { it.marker } to
                            live.filter { it.status != Status.SENT }.map {
                                mapOf<String, Any?>(
                                    "marker" to it.marker,
                                    "status" to it.status.name,
                                )
                            }
                    )
            mapOf(
                "capacity" to capacity,
                "pendingCapacity" to capacity,
                "pendingScope" to "queued plus currently sending",
                "pendingCount" to pending.get(),
                "offered" to offered.get(),
                "queueSize" to queue.size,
                "queueOverflow" to overflow.get(),
                "historyCapacity" to historyCapacity,
                "historyCount" to historyCount,
                "historyOverflow" to historyOverflow.get(),
                "firstHistoryOverflow" to firstHistoryOverflow,
                "sentCount" to sent.get().toInt(),
                "sentMarkers" to records.first,
                "undeliveredMarkers" to records.second,
                "markerPlan" to plan?.fields(),
                "evidenceStorage" to if (evidencePath == null) "memory" else "file",
                "evidencePath" to evidencePath?.toString(),
                "evidenceIndexBytesPerIdentity" to if (evidencePath == null) 0 else 8,
                "retainedPayloadCount" to retainedPayloadCount,
                "retainedEvidenceCount" to if (evidencePath == null) historyCount else retainedEvidenceCount,
                "terminalEvidenceCapacity" to capacity,
                "terminalEvidenceCount" to terminalEvidence.size,
                "evidenceOverflow" to evidenceOverflow.get(),
                "firstEvidenceOverflow" to firstEvidenceOverflow,
                "evidenceError" to evidenceError,
            )
        }

    fun capacityFailures(): List<String> =
        buildList {
            if (historyOverflow.get() != 0L) {
                add("완료 표식 송신 누적 계획 초과: capacity=$historyCapacity, count=${historyOverflow.get()}")
            }
            if (evidenceOverflow.get() != 0L) {
                add("완료 표식 원문 증거 큐 초과: capacity=$capacity, count=${evidenceOverflow.get()}")
            }
            evidenceError?.let(::add)
        }

    /** The owner must join the sender before this final evidence flush and file close. */
    override fun close() {
        if (evidencePath == null) return
        val remaining =
            synchronized(history) {
                while (queue.poll() != null) check(pending.decrementAndGet() >= 0)
                active.values.toList().onEach { it.jfrEvent = null }
            }
        remaining.forEach { appendEvidence(it) }
        flushEvidence()
        try {
            journal?.close()
        } catch (failure: Throwable) {
            evidenceError = "완료 표식 증거 종료 실패: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }
}

/** 누적 exact 원문 index다. 네트워크의 순간 대기 큐가 아니다. */
internal class HttpPerformanceReceivedMarkerHistory(
    private val runId: String,
    val capacityPerMember: Int,
) {
    private val values = ConcurrentHashMap<Pair<Int, Long>, HttpPerformanceCompletion>()
    private val counts = IntArray(3)
    private val identicalDuplicates = AtomicLong()

    init {
        require(capacityPerMember in 1..MAX_MARKER_PLAN_IDENTITIES)
    }

    @Synchronized
    fun observe(marker: HttpPerformanceCompletion): Boolean {
        require(marker.runId == runId) { "다른 실행의 완료 표식" }
        require(marker.memberId in 0..2) { "완료 표식 멤버 범위 초과" }
        val key = marker.memberId to marker.admissionSequence
        val previous = values[key]
        if (previous != null) {
            require(previous == marker) { "완료 표식 식별 충돌: $key" }
            identicalDuplicates.incrementAndGet()
            return false
        }
        require(counts[marker.memberId] < capacityPerMember) {
            "완료 표식 수신 누적 계획 초과: capacity=$capacityPerMember, marker=$marker"
        }
        values[key] = marker
        counts[marker.memberId]++
        return true
    }

    fun markers(): List<HttpPerformanceCompletion> = values.values.sortedWith(compareBy({ it.memberId }, { it.admissionSequence }))

    fun duplicateCount(): Long = identicalDuplicates.get()
}

/** 테스트 관측 경로 전체에만 적용한다. 상태가 있는 idle 인스턴스는 스레드마다 새로 만든다. */
internal enum class HttpPerformanceMarkerIdlePolicy(
    val description: String,
) {
    SLEEP_1MS("SleepingMillisIdleStrategy(1)"),
    BACKOFF("BackoffIdleStrategy(10,5,1000ns,100000ns)"),
    ;

    fun newIdleStrategy(): IdleStrategy =
        when (this) {
            SLEEP_1MS -> SleepingMillisIdleStrategy(1)
            BACKOFF -> BackoffIdleStrategy(10, 5, 1_000, 100_000)
        }
}

/** 송신 노드의 같은 nanoTime 시계에서 계산한 차분만 담는다. 전송 payload에는 넣지 않는다. */
internal data class HttpPerformanceMarkerLocalSpan(
    val runId: String,
    val memberId: Int,
    val userId: String,
    val requestId: String,
    val orderId: String,
    val admissionSequence: Long,
    val queueWaitNs: Long,
    val encodeNs: Long,
    val offerToAcceptedNs: Long,
    val dequeueToAcceptedNs: Long,
    val enqueueToAcceptedNs: Long,
)

/** 송신 스레드 하나가 기록하고 측정 밖의 보고 스레드가 복사한다. 넘치면 보고서만 무효로 한다. */
internal class HttpPerformanceMarkerLocalSpans(
    private val capacity: Int = HTTP_PERFORMANCE_MARKER_CAPACITY,
) {
    private val values = ArrayList<HttpPerformanceMarkerLocalSpan>()
    private var overflow = 0L

    init {
        require(capacity in 1..HTTP_PERFORMANCE_MARKER_CAPACITY)
    }

    @Synchronized
    fun record(
        marker: HttpPerformanceCompletion,
        enqueuedNs: Long,
        dequeuedNs: Long,
        encodeStartedNs: Long,
        encodeCompletedNs: Long,
        firstOfferNs: Long,
        acceptedNs: Long,
    ) {
        fun elapsed(
            start: Long,
            end: Long,
        ): Long = (end - start).also { require(it >= 0) { "완료 표식 송신 로컬 구간의 시계 차분이 음수입니다" } }
        val queueWait = elapsed(enqueuedNs, dequeuedNs)
        elapsed(dequeuedNs, encodeStartedNs)
        val encoding = elapsed(encodeStartedNs, encodeCompletedNs)
        elapsed(encodeCompletedNs, firstOfferNs)
        val offers = elapsed(firstOfferNs, acceptedNs)
        val dequeueToAccepted = elapsed(dequeuedNs, acceptedNs)
        val enqueueToAccepted = elapsed(enqueuedNs, acceptedNs)
        if (values.size == capacity) {
            overflow++
            return
        }
        values.add(
            HttpPerformanceMarkerLocalSpan(
                marker.runId,
                marker.memberId,
                marker.userId,
                marker.requestId,
                marker.orderId,
                marker.admissionSequence,
                queueWait,
                encoding,
                offers,
                dequeueToAccepted,
                enqueueToAccepted,
            ),
        )
    }

    @Synchronized
    fun snapshot(): List<HttpPerformanceMarkerLocalSpan> = values.toList()

    @Synchronized
    fun overflowCount(): Long = overflow
}

/** 노드 시계를 포함하지 않는 버전 고정 완료 표식이다. */
internal object HttpPerformanceMarkerCodec {
    fun encode(marker: HttpPerformanceCompletion): ByteArray = Encoder().encode(marker)

    /** 송신 스레드 하나가 버퍼를 재사용하며 반환한 바이트는 다음 인코딩과 공유하지 않는다. */
    class Encoder {
        private val bytes = ByteArrayOutputStream(MAX_MARKER_BYTES)
        private val output = DataOutputStream(bytes)

        fun encode(marker: HttpPerformanceCompletion): ByteArray {
            bytes.reset()
            output.writeInt(MARKER_MAGIC)
            writeText(marker.runId)
            writeText(marker.role)
            writeText(marker.userId)
            writeText(marker.requestId)
            writeText(marker.orderId)
            output.writeInt(marker.memberId)
            output.writeLong(marker.admissionSequence)
            output.writeLong(marker.logPosition)
            output.writeLong(marker.tradeCount)
            output.writeLong(marker.settlementCount)
            return bytes.toByteArray().also { require(it.size <= MAX_MARKER_BYTES) }
        }

        private fun writeText(value: String) {
            require(value.isNotBlank() && value.length <= 256) { "완료 표식 문자열 크기가 잘못됐습니다" }
            output.writeUTF(value)
        }
    }

    fun decode(bytes: ByteArray): HttpPerformanceCompletion {
        require(bytes.size <= MAX_MARKER_BYTES) { "완료 표식 크기 초과" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MARKER_MAGIC) { "알 수 없는 완료 표식 형식" }
            val text = (1..5).map { input.readUTF().also { value -> require(value.isNotBlank() && value.length <= 256) } }
            HttpPerformanceCompletion(
                runId = text[0],
                memberId = input.readInt(),
                role = text[1],
                userId = text[2],
                requestId = text[3],
                orderId = text[4],
                admissionSequence = input.readLong(),
                logPosition = input.readLong(),
                tradeCount = input.readLong(),
                settlementCount = input.readLong(),
            ).also {
                require(it.memberId in 0..2 && it.role in setOf("LEADER", "FOLLOWER"))
                require(it.admissionSequence > 0 && it.logPosition >= 0 && it.tradeCount >= 0 && it.settlementCount >= 0)
                require(input.available() == 0) { "완료 표식 뒤에 불필요한 바이트가 있습니다" }
            }
        }
    }
}

private fun markerDriver(
    path: Path,
    onFailure: (String) -> Unit,
    idlePolicy: HttpPerformanceMarkerIdlePolicy,
): MediaDriver {
    require(!Files.exists(path)) { "기존 완료 관찰 실행 경로는 재사용하지 않습니다: $path" }
    return MediaDriver.launch(
        MediaDriver
            .Context()
            .aeronDirectoryName(path.toString())
            .dirDeleteOnStart(false)
            .dirDeleteOnShutdown(true)
            .threadingMode(ThreadingMode.SHARED)
            .sharedIdleStrategy(idlePolicy.newIdleStrategy())
            .errorHandler { onFailure("marker MediaDriver: ${it.javaClass.simpleName}: ${it.message}") },
    )
}

/** 업무 생산자 하나와 네트워크 송신자 하나가 사용한다. 관찰 실패는 업무 처리를 바꾸지 않는다. */
internal class HttpPerformanceMarkerSender(
    private val runRoot: Path,
    private val endpoint: String,
    val capacity: Int = HTTP_PERFORMANCE_MARKER_CAPACITY,
    private val idlePolicy: HttpPerformanceMarkerIdlePolicy = HttpPerformanceMarkerIdlePolicy.SLEEP_1MS,
    private val detailedTracing: Boolean = true,
    markerPlan: HttpPerformanceMarkerPlan? = null,
    private val requestJfr: HttpPerformanceJfr? = null,
) : AutoCloseable {
    private val outbox = HttpPerformanceMarkerOutbox(capacity, markerPlan, runRoot.resolve("marker-evidence.bin"))
    private val stopping = AtomicBoolean()
    private val failures = CopyOnWriteArrayList<String>()
    private val localSpans = HttpPerformanceMarkerLocalSpans()
    private val thread = Thread(::send, "http-performance-marker-sender").apply { isDaemon = true }

    fun start() = thread.start()

    fun offer(marker: HttpPerformanceCompletion) {
        val event = requestJfr?.let { observeJfr { it.beginMarkerDelivery(marker) } }
        outbox.offer(marker, if (detailedTracing) System.nanoTime() else event?.enqueuedNs, event)
    }

    private inline fun <T> observeJfr(action: () -> T): T? =
        try {
            action()
        } catch (error: Throwable) {
            failures.add("완료 표식 JFR 관찰 실패: ${error.javaClass.simpleName}: ${error.message}")
            null
        }

    fun summary(): Map<String, Any?> =
        outbox.summary() +
            mapOf(
                "markerIdlePolicy" to idlePolicy.name,
                "markerIdleStrategy" to idlePolicy.description,
                "detailedTracing" to detailedTracing,
                "senderLocalSpanCapacity" to HTTP_PERFORMANCE_MARKER_CAPACITY,
                "senderLocalSpanClock" to "sender node-local System.nanoTime differences only; never subtract receiver clock",
                "senderLocalSpans" to localSpans.snapshot(),
                "senderLocalSpanOverflow" to localSpans.overflowCount(),
                "failures" to (failures.toList() + outbox.capacityFailures()),
            )

    val isDrained: Boolean get() = outbox.isDrained && failures.isEmpty()

    private fun send() {
        try {
            markerDriver(runRoot.resolve("marker-driver"), failures::add, idlePolicy).use { driver ->
                Aeron.connect(Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())).use { aeron ->
                    aeron.addPublication("aeron:udp?endpoint=$endpoint", MARKER_STREAM).use { publication ->
                        val idle = idlePolicy.newIdleStrategy()
                        val encoder = HttpPerformanceMarkerCodec.Encoder()
                        while (!stopping.get() || !outbox.isEmpty) {
                            outbox.flushEvidence()
                            val queued = outbox.poll()
                            if (queued == null) {
                                idle.idle(0)
                                continue
                            }
                            idle.reset()
                            var delivered = false
                            try {
                                val marker = queued.marker
                                queued.jfrEvent?.let { event -> observeJfr { requestJfr?.markerDequeued(event) } }
                                val dequeuedNs = if (detailedTracing) System.nanoTime() else 0L
                                val encodeStartedNs = if (detailedTracing) System.nanoTime() else 0L
                                val bytes = encoder.encode(marker)
                                val encodeCompletedNs = if (detailedTracing) System.nanoTime() else 0L
                                val buffer = UnsafeBuffer(bytes)
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                                val firstOfferNs = if (detailedTracing) System.nanoTime() else 0L
                                queued.jfrEvent?.let { event -> observeJfr { requestJfr?.markerFirstOffer(event) } }
                                while (!delivered && System.nanoTime() < deadline && !stopping.get()) {
                                    val result = publication.offer(buffer)
                                    if (result > 0) {
                                        delivered = true
                                        val acceptedNs = if (detailedTracing) System.nanoTime() else 0L
                                        queued.jfrEvent?.let { event -> observeJfr { requestJfr?.completeMarkerDelivery(event) } }
                                        if (detailedTracing) {
                                            try {
                                                localSpans.record(
                                                    marker,
                                                    checkNotNull(queued.enqueuedNs),
                                                    dequeuedNs,
                                                    encodeStartedNs,
                                                    encodeCompletedNs,
                                                    firstOfferNs,
                                                    acceptedNs,
                                                )
                                            } catch (error: IllegalArgumentException) {
                                                failures.add("완료 표식 로컬 구간: ${error.message}")
                                            }
                                        }
                                        idle.reset()
                                    } else if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                                        break
                                    } else {
                                        idle.idle(0)
                                    }
                                }
                                if (!delivered) failures.add("완료 표식 전송 실패: member=${marker.memberId}, sequence=${marker.admissionSequence}")
                            } finally {
                                outbox.finish(queued, delivered)
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            failures.add("완료 표식 송신기: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    override fun close() {
        // 호출자가 구독을 유지한 상태에서 isDrained를 먼저 기다린다.
        stopping.set(true)
        try {
            thread.join(5_000)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            failures.add("완료 표식 송신 스레드 종료 대기 중단")
            return
        }
        if (thread.isAlive) {
            failures.add("완료 표식 송신 스레드 종료 미확인")
            return
        }
        outbox.close()
    }
}

/** HTTP 발생기와 같은 JVM의 nanoTime을 기록한다. 멤버별 보존량에는 고정 상한이 있다. */
internal class HttpPerformanceMarkerReceiver(
    private val runId: String,
    private val runRoot: Path,
    private val onMarker: (HttpPerformanceCompletion, Long) -> Unit,
    private val idlePolicy: HttpPerformanceMarkerIdlePolicy = HttpPerformanceMarkerIdlePolicy.SLEEP_1MS,
    private val markerPlan: HttpPerformanceMarkerPlan? = null,
) : AutoCloseable {
    val port: Int = DatagramSocket(0).use { it.localPort }
    private val stopping = AtomicBoolean()
    private val failures = CopyOnWriteArrayList<String>()
    private val received =
        HttpPerformanceReceivedMarkerHistory(
            runId,
            markerPlan?.also { require(it.runId == runId) }?.identitiesPerMember ?: HTTP_PERFORMANCE_MARKER_CAPACITY,
        )
    private val driver = markerDriver(runRoot.resolve("receiver-driver"), failures::add, idlePolicy)
    private val aeron = Aeron.connect(Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()))
    private val subscription = aeron.addSubscription("aeron:udp?endpoint=0.0.0.0:$port", MARKER_STREAM)
    private val thread = Thread(::receive, "http-performance-marker-receiver").apply { isDaemon = true }

    init {
        thread.start()
    }

    fun markers(): List<HttpPerformanceCompletion> = received.markers()

    fun errors(): List<String> = failures.toList()

    fun manifest(): Map<String, Any?> =
        mapOf(
            "transport" to "Aeron UDP",
            "streamId" to MARKER_STREAM,
            "receiverEndpoint" to "0.0.0.0:$port",
            "senderEndpoint" to "host.docker.internal:$port",
            "receiverJvm" to "HTTP load generator JVM",
            "clock" to "System.nanoTime in HTTP load generator JVM",
            "driverThreading" to "SHARED",
            "idle" to idlePolicy.description,
            "markerIdlePolicy" to idlePolicy.name,
            "additionalMediaDriverPerNode" to true,
            "additionalMediaDriverInLoadJvm" to true,
            "capacityPerMember" to received.capacityPerMember,
            "capacityScope" to "cumulative exact identities per member for this run; not a pending queue",
            "applicationPendingQueue" to false,
            "markerPlan" to markerPlan?.fields(),
            "duplicates" to received.duplicateCount(),
        )

    private fun receive() {
        val handler =
            FragmentAssembler { buffer, offset, length, _ ->
                try {
                    val marker = HttpPerformanceMarkerCodec.decode(ByteArray(length).also { buffer.getBytes(offset, it) })
                    val receivedNs = System.nanoTime()
                    if (received.observe(marker)) onMarker(marker, receivedNs)
                } catch (error: Throwable) {
                    failures.add("완료 표식 수신기: ${error.javaClass.simpleName}: ${error.message}")
                }
            }
        try {
            val idle = idlePolicy.newIdleStrategy()
            while (!stopping.get()) {
                idle.idle(subscription.poll(handler, 100))
            }
        } catch (error: Throwable) {
            if (!stopping.get()) failures.add("완료 표식 구독: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    override fun close() {
        stopping.set(true)
        thread.join(5_000)
        if (thread.isAlive) failures.add("완료 표식 수신 스레드 종료 미확인")
        subscription.close()
        aeron.close()
        driver.close()
    }
}
