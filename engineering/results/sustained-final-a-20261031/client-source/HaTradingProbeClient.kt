package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.Quantity
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.Side
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.HdrHistogram.Histogram
import org.agrona.concurrent.NanoClock
import org.agrona.concurrent.SystemNanoClock
import org.agrona.concurrent.UnsafeBuffer
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class HaProbeAdmission(
    val index: Int,
    val admissionSequence: Long,
)

internal data class HaProbeAcknowledgement(
    val correlation: Long,
    val through: Long,
    val bytes: ByteArray,
)

internal interface HaProbeWire {
    val mode: String
    val requestWindow: Long get() = Long.MAX_VALUE

    fun configure(plan: HaProbeRunPlan) {
        require(!plan.partialMaker) { "partial-maker requires bounded wire3" }
    }

    fun initialize(connection: HaProbeConnection)

    fun fund(
        connection: HaProbeConnection,
        command: DevelopmentBalanceCommandPayload,
    )

    fun open(connection: HaProbeConnection)

    fun acknowledge(
        connection: HaProbeConnection,
        side: Side,
        through: Long,
    )

    fun encodeAcknowledgement(
        side: Side,
        through: Long,
    ): HaProbeAcknowledgement? = null

    fun acknowledgement(
        bytes: ByteArray,
        expected: HaProbeAcknowledgement,
    ): Long? = null

    fun encodeOrder(
        index: Int,
        side: Side,
    ): ByteArray

    fun admission(
        bytes: ByteArray,
        side: Side,
    ): HaProbeAdmission?
}

/** A bounded sample budget is explicit even for the duration-based workload. */
internal data class HaProbeRunPlan(
    val partialMaker: Boolean,
    val capacity: Int,
    val warmupSeconds: Int = 0,
    val measureSeconds: Int = 0,
) {
    init {
        if (partialMaker) {
            val maximum = if (measureSeconds == 600) 64_000_000 else 4_000_000
            require(capacity in 3..maximum) { "partial-maker maxSamples must be between 3 and $maximum" }
            require(warmupSeconds in 20..30 && (measureSeconds in 10..15 || measureSeconds == 600))
        } else {
            require(capacity in 1..6_000 && warmupSeconds == 0 && measureSeconds == 0)
        }
        TradingEngineProbeWorkload.quoteFunding(capacity)
        TradingEngineProbeWorkload.markets.indices.forEach { baseFunding(it) }
    }

    val sustained: Boolean get() = partialMaker && measureSeconds == 600
    val warmupCapacity: Int get() = if (sustained) minOf(capacity, 2_000_000) else capacity
    val workload: String get() = if (partialMaker) "partial-maker" else "full-maker"
    val sellOrders: Int get() = if (partialMaker) TradingEngineProbeWorkload.markets.size else capacity

    fun baseFunding(ordinal: Int): Long {
        val workload = TradingEngineProbeWorkload
        require(ordinal in workload.markets.indices)
        return Math.multiplyExact(workload.countForMarket(capacity, ordinal).toLong(), workload.quantity(workload.markets[ordinal]).value)
    }

    fun orderQuantity(
        index: Int,
        side: Side,
    ): Quantity =
        if (partialMaker && side == Side.SELL) {
            Quantity(baseFunding(index))
        } else {
            TradingEngineProbeWorkload.quantity(TradingEngineProbeWorkload.market(index))
        }

    fun remainingQuote(actualBuys: Int): Long {
        require(actualBuys in 0..capacity)
        return Math.subtractExact(
            TradingEngineProbeWorkload.quoteFunding(capacity),
            Math.multiplyExact(actualBuys.toLong(), TradingEngineProbeWorkload.RESERVE_PER_BUY),
        )
    }

    fun remainingBase(
        actualBuys: Int,
        ordinal: Int,
    ): Long {
        require(actualBuys in 0..capacity)
        val workload = TradingEngineProbeWorkload
        return Math.subtractExact(
            baseFunding(ordinal),
            Math.multiplyExact(workload.countForMarket(actualBuys, ordinal).toLong(), workload.quantity(workload.markets[ordinal]).value),
        )
    }

    companion object {
        fun from(
            options: TradingEngineProbeOptions,
            environment: Map<String, String> = System.getenv(),
        ): HaProbeRunPlan =
            when (environment["HA_PROBE_WORKLOAD"] ?: "full-maker") {
                "full-maker" -> {
                    val durationKeys = listOf("HA_PROBE_MAX_SAMPLES", "HA_PROBE_WARMUP_SECONDS", "HA_PROBE_MEASURE_SECONDS")
                    require(durationKeys.none(environment::containsKey)) {
                        "duration options only apply to partial-maker"
                    }
                    HaProbeRunPlan(false, options.total)
                }

                "partial-maker" -> {
                    HaProbeRunPlan(
                        true,
                        requireNotNull(
                            environment["HA_PROBE_MAX_SAMPLES"],
                        ) { "partial-maker requires explicit HA_PROBE_MAX_SAMPLES" }.toInt(),
                        requireNotNull(environment["HA_PROBE_WARMUP_SECONDS"]).toInt(),
                        requireNotNull(environment["HA_PROBE_MEASURE_SECONDS"]).toInt(),
                    )
                }

                else -> {
                    error("unknown HA probe workload")
                }
            }
    }
}

/** One outstanding ACK; only its verified response can release request-window credit. */
internal class HaProbeAckCoordinator(
    private val wire: HaProbeWire,
    private val side: Side,
    initialFloor: Long,
) {
    var confirmedThrough: Long = initialFloor
        private set
    private var desiredThrough = initialFloor
    private var pending: HaProbeAcknowledgement? = null
    private var sent = false
    private var began = 0L
    var requests = 0
        private set
    val awaitingReply: Boolean get() = pending != null

    fun observeThrough(through: Long) {
        require(through >= desiredThrough) { "receipt prefix moved backwards" }
        desiredThrough = through
    }

    fun accept(bytes: ByteArray): Boolean {
        val expected = pending ?: return false
        val retired = wire.acknowledgement(bytes, expected) ?: return false
        check(sent && retired == expected.through) { "ACK did not confirm the requested contiguous prefix" }
        confirmedThrough = retired
        pending = null
        sent = false
        return true
    }

    fun pump(
        offer: (ByteArray) -> Boolean,
        now: Long,
        flush: Boolean = false,
    ) {
        if (pending == null && desiredThrough > confirmedThrough &&
            (flush || desiredThrough - confirmedThrough >= TradingEngineProbeWorkload.ACK_EVERY)
        ) {
            val next = wire.encodeAcknowledgement(side, desiredThrough)
            if (next == null) {
                confirmedThrough = desiredThrough
                return
            }
            check(next.through == desiredThrough)
            pending = next
            began = now
            requests++
        }
        pending?.let {
            check(now - began < TimeUnit.SECONDS.toNanos(30)) { "ACK result unconfirmed: through=${it.through} transportAccepted=$sent" }
            if (!sent) sent = offer(it.bytes)
        }
    }
}

/** One connection owner and a client-owned Driver; no transport reuse from any cluster node. */
internal class HaProbeConnection(
    private val config: DevelopmentFundingSettings,
) : AutoCloseable {
    private val root = Files.createTempDirectory(config.runRoot, "ha-probe-client-")
    private val driver =
        MediaDriver.launch(
            MediaDriver
                .Context()
                .aeronDirectoryName(root.resolve("driver").toString())
                .threadingMode(ThreadingMode.SHARED)
                .sharedIdleStrategy(TradingAeronRuntime.idleStrategy())
                .dirDeleteOnStart(false)
                .dirDeleteOnShutdown(false),
        )
    var onFrame: (ByteArray) -> Unit = {}
    private val egressHost =
        if (config.advertisedHost == "127.0.0.1" || config.advertisedHost == "localhost") {
            "127.0.0.1"
        } else {
            java.net.InetAddress
                .getAllByName(
                    java.net.InetAddress
                        .getLocalHost()
                        .hostName,
                ).filterIsInstance<java.net.Inet4Address>()
                .first()
                .hostAddress
        }
    private val cluster =
        AeronCluster.connect(
            AeronCluster
                .Context()
                .aeronDirectoryName(driver.aeronDirectoryName())
                .ingressChannel("aeron:udp?term-length=${TradingAeronRuntime.TERM_LENGTH_BYTES}")
                .ingressEndpoints(config.ingressEndpoints)
                .egressChannel("aeron:udp?endpoint=$egressHost:0")
                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(60))
                .egressListener { _, _, buffer, offset, length, _ ->
                    require(length in 8..4096)
                    onFrame(ByteArray(length).also { buffer.getBytes(offset, it) })
                },
        )
    private var keepAlive = System.nanoTime()

    fun offer(bytes: ByteArray): Boolean {
        val result = cluster.offer(UnsafeBuffer(bytes), 0, bytes.size)
        check(
            result > 0 || result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION || result == Publication.NOT_CONNECTED,
        ) {
            "terminal transport result $result"
        }
        return result > 0
    }

    fun poll(): Int {
        val work = cluster.pollEgress()
        val now = System.nanoTime()
        if (now - keepAlive >= TimeUnit.MILLISECONDS.toNanos(500)) {
            cluster.sendKeepAlive()
            keepAlive = now
        }
        return work
    }

    fun request(
        bytes: ByteArray,
        accepts: (ByteArray) -> Boolean,
    ) {
        val previous = onFrame
        var received = false
        onFrame = { frame -> if (accepts(frame)) received = true else previous(frame) }
        var sent = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        try {
            while (!received && System.nanoTime() - deadline < 0) {
                if (!sent) sent = offer(bytes)
                if (poll() == 0) Thread.onSpinWait()
            }
            check(received) { "control result unconfirmed; request was transportAccepted=$sent" }
        } finally {
            onFrame = previous
        }
    }

    override fun close() {
        try {
            cluster.close()
        } finally {
            driver.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

internal data class HaProbeCompletion(
    val index: Int,
    val sequence: Long,
    val leaderNs: Long,
    val allReplicasNs: Long,
)

/** The owner consumes completed entries; the collector never retains an entire run. */
internal class HaProbeMarkerCollector(
    private val capacity: Int,
) {
    private data class Arrival(
        var position: Long,
        val times: LongArray = LongArray(3),
        var mask: Int = 0,
        var leaderNs: Long = 0,
        var index: Int? = null,
    )

    private val lock = Any()
    private val entries = HashMap<Long, Arrival>()
    private val completed = ArrayDeque<HaProbeCompletion>()

    @Volatile private var hasCompleted = false

    private val memberPositions = LongArray(3) { -1L }
    private val memberSequences = LongArray(3)
    private val lockAttempts = AtomicLong()
    private var lockWaitSamples = 0L
    private var lockWaitTotalNs = 0L
    private var lockWaitMaxNs = 0L
    private var observedFrames = 0L
    private var registeredResponses = 0L
    private var drainedCompletions = 0L
    var peakEntries = 0
        private set
    val retained: Int get() = synchronized(lock) { entries.size + completed.size }

    init {
        require(capacity > 0)
    }

    private inline fun <T> locked(action: () -> T): T {
        val sampled = lockAttempts.incrementAndGet() % 256L == 0L
        val began = if (sampled) System.nanoTime() else 0L
        return synchronized(lock) {
            if (sampled) {
                val elapsed = System.nanoTime() - began
                check(elapsed >= 0) { "collector client-local clock moved backwards" }
                lockWaitSamples++
                lockWaitTotalNs += elapsed
                lockWaitMaxNs = maxOf(lockWaitMaxNs, elapsed)
            }
            action()
        }
    }

    fun summary(): Map<String, Any?> =
        synchronized(lock) {
            mapOf(
                "scope" to
                    "cumulative client collector; all frames correlated; lock timing sampled every 256 acquired operations; empty completion polls excluded",
                "observedFrames" to observedFrames,
                "registeredResponses" to registeredResponses,
                "drainedCompletions" to drainedCompletions,
                "timingSampleEvery" to 256,
                "lockWaitSamples" to lockWaitSamples,
                "lockWaitTotalNs" to lockWaitTotalNs,
                "lockWaitMaxNs" to lockWaitMaxNs,
                "peakEntries" to peakEntries,
                "retained" to entries.size + completed.size,
            )
        }

    private fun arrival(
        sequence: Long,
        position: Long,
    ): Arrival {
        require(sequence > 0 && position >= 0)
        return entries[sequence]?.also {
            if (it.position == -1L) it.position = position
            check(it.position == position) { "replicas reported different positions for admission $sequence" }
        }
            ?: Arrival(position).also {
                check(entries.size + completed.size < capacity) { "completion collector capacity exceeded" }
                entries[sequence] = it
                peakEntries = maxOf(peakEntries, entries.size + completed.size)
            }
    }

    fun observe(
        member: Int,
        sequence: Long,
        position: Long,
        leader: Boolean,
        at: Long,
    ) = locked {
        observedFrames++
        require(member in 0..2 && at > 0 && sequence > 0 && position >= 0)
        if (position == memberPositions[member] && sequence == memberSequences[member]) return@locked
        check(position > memberPositions[member]) { "completion stream position did not increase for member $member" }
        memberPositions[member] = position
        memberSequences[member] = sequence
        val entry = arrival(sequence, position)
        check(entry.mask and (1 shl member) == 0) { "duplicate completion member" }
        entry.times[member] = at
        entry.mask = entry.mask or (1 shl member)
        if (leader && entry.leaderNs == 0L) entry.leaderNs = at
        finish(sequence, entry)
    }

    fun register(
        sequence: Long,
        index: Int,
    ) = locked {
        registeredResponses++
        require(sequence > 0 && index >= 0)
        val entry =
            entries[sequence] ?: run {
                check(entries.size + completed.size < capacity) { "completion collector capacity exceeded" }
                // The response can precede every node marker. Its position is learned from the first marker.
                Arrival(-1).also {
                    entries[sequence] = it
                    peakEntries = maxOf(peakEntries, entries.size + completed.size)
                }
            }
        check(entry.index == null) { "admission sequence was assigned to more than one response" }
        entry.index = index
        finish(sequence, entry)
    }

    private fun finish(
        sequence: Long,
        entry: Arrival,
    ) {
        val index = entry.index ?: return
        if (entry.mask != 7) return
        check(entry.leaderNs > 0) { "no leader completion marker for admission $sequence" }
        entries.remove(sequence)
        completed.addLast(HaProbeCompletion(index, sequence, entry.leaderNs, entry.times.maxOrNull()!!))
        hasCompleted = true
    }

    fun drain(consumer: (HaProbeCompletion) -> Unit): Int {
        if (!hasCompleted) return 0
        return locked {
            var count = 0
            while (completed.isNotEmpty()) {
                consumer(completed.removeFirst())
                count++
            }
            // The same monitor excludes a publisher until the empty state is visible.
            hasCompleted = false
            drainedCompletions += count
            count
        }
    }
}

internal class HaProbeMarkerReceiver(
    port: Int,
    capacity: Int,
) : AutoCloseable {
    private val lock = Any()
    private val server =
        ServerSocket().also {
            it.receiveBufferSize = 4 * 1024 * 1024
            it.bind(InetSocketAddress("0.0.0.0", port), 3)
        }
    private val sockets = mutableListOf<Socket>()
    private val collector = HaProbeMarkerCollector(capacity)
    private val connectedMembers = BooleanArray(3)
    private val failure = AtomicReference<String?>()
    private var malformedFrames = 0

    @Volatile private var closing = false
    val receiveBufferBytes: Int get() = synchronized(lock) { sockets.minOfOrNull { it.receiveBufferSize } ?: server.receiveBufferSize }
    val malformed: Int get() = synchronized(lock) { malformedFrames }

    // Each member has one persistent stream and one reader. Reads continue while the
    // request owner is waiting for ACK/control responses or driving Aeron egress.
    private val readers =
        List(3) { reader ->
            Thread({
                try {
                    server.accept().use { socket ->
                        synchronized(lock) { sockets.add(socket) }
                        val input = socket.getInputStream()
                        val bytes = ByteArray(32)
                        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        var streamMember: Int? = null
                        while (!closing) {
                            var received = 0
                            while (received < bytes.size) {
                                val count = input.read(bytes, received, bytes.size - received)
                                if (count < 0) throw EOFException("completion TCP stream ended inside observation run: bytes=$received")
                                received += count
                            }
                            val at = System.nanoTime()
                            buffer.clear()
                            val magic = buffer.int
                            val member = buffer.int
                            val sequence = buffer.long
                            val position = buffer.long // Canonical applied log position is not a timestamp.
                            val leader = buffer.int
                            val reserved = buffer.int
                            synchronized(lock) {
                                if (magic != HaTradingProbeMarkers.MAGIC || member !in 0..2 || sequence <= 0 || position < 0 ||
                                    leader !in 0..1 ||
                                    reserved != 0
                                ) {
                                    malformedFrames++
                                    error("malformed completion TCP frame")
                                }
                                if (streamMember == null) {
                                    check(!connectedMembers[member]) { "duplicate completion TCP stream for member $member" }
                                    connectedMembers[member] = true
                                    streamMember = member
                                } else {
                                    check(streamMember == member) { "completion TCP stream changed its member" }
                                }
                            }
                            collector.observe(member, sequence, position, leader == 1, at)
                        }
                    }
                } catch (error: Throwable) {
                    if (!closing) failure.compareAndSet(null, "completion TCP observation failed: $error")
                }
            }, "test-ha-marker-reader-$reader").also {
                it.isDaemon = true
                it.start()
            }
        }

    fun poll(): Int {
        check(failure.get() == null) { checkNotNull(failure.get()) }
        return 0
    }

    fun register(
        sequence: Long,
        index: Int,
    ) = collector.register(sequence, index)

    fun drain(consumer: (HaProbeCompletion) -> Unit): Int = collector.drain(consumer)

    val retained: Int get() = collector.retained
    val peakEntries: Int get() = collector.peakEntries

    fun summary(): Map<String, Any?> = collector.summary()

    override fun close() {
        closing = true
        server.close()
        synchronized(lock) { sockets.toList() }.forEach { it.close() }
        readers.forEach { it.join(2_000) }
        check(readers.none { it.isAlive }) { "completion TCP readers did not stop" }
    }
}

internal data class HaProbeThroughput(
    val samples: Int,
    val rawSpanNs: Long,
    val elapsedNs: Long,
    val cohortElapsedNs: Long,
) {
    val tps: Double get() = samples * 1e9 / elapsedNs
    val rawSpanTps: Double get() = samples * 1e9 / rawSpanNs
    val cohortTps: Double get() = samples * 1e9 / cohortElapsedNs
}

/** All times belong to the receiving client clock. Duration windows include idle/ACK time inside that window. */
internal fun haProbeThroughput(
    samples: Int,
    durationWindow: Boolean,
    phaseStartNs: Long,
    firstAttemptNs: Long,
    offeringStoppedNs: Long,
    lastObservationNs: Long,
    cohortEndNs: Long,
    includeFinalAck: Boolean = false,
): HaProbeThroughput {
    val rawSpan = lastObservationNs - firstAttemptNs
    val offeringElapsed = offeringStoppedNs - phaseStartNs
    val observedElapsed = lastObservationNs - phaseStartNs
    val cohortElapsed = cohortEndNs - phaseStartNs
    require(samples > 0 && firstAttemptNs - phaseStartNs >= 0 && rawSpan > 0)
    require(offeringElapsed >= 0 && cohortElapsed >= maxOf(offeringElapsed, observedElapsed))
    return HaProbeThroughput(
        samples,
        rawSpan,
        if (includeFinalAck) {
            cohortElapsed
        } else if (durationWindow) {
            maxOf(offeringElapsed, observedElapsed)
        } else {
            rawSpan
        },
        if (durationWindow) cohortElapsed else cohortEndNs - firstAttemptNs,
    )
}

/** Disjoint observed states at loop transitions; their times are not independent gate timers. */
internal class HaProbeGateDwell(
    phaseStartNs: Long,
    private val windowEndNs: Long? = null,
) {
    private val totalNs = LongArray(8)
    private val visits = LongArray(8)
    private val maxNs = LongArray(8)
    private var previousNs = phaseStartNs
    private var previousMask = 0
    private var continuousNs = 0L
    private var closed = false

    init {
        require(windowEndNs == null || windowEndNs >= phaseStartNs)
    }

    fun observeIfChanged(
        mask: Int,
        nanoTime: NanoClock = SystemNanoClock.INSTANCE,
    ) {
        require(mask in 0..7)
        if (closed || mask == previousMask) return
        observe(nanoTime.nanoTime(), mask)
    }

    fun observe(
        nowNs: Long,
        mask: Int,
    ) {
        require(mask in 0..7)
        if (closed) return
        val at = windowEndNs?.let { minOf(nowNs, it) } ?: nowNs
        require(at >= previousNs)
        val elapsed = at - previousNs
        totalNs[previousMask] += elapsed
        continuousNs += elapsed
        if (mask != previousMask) {
            finishDwell()
        }
        previousNs = at
        previousMask = mask
    }

    fun close(nowNs: Long) {
        if (closed) return
        observe(nowNs, previousMask)
        finishDwell()
        closed = true
    }

    private fun finishDwell() {
        if (continuousNs > 0) visits[previousMask]++
        maxNs[previousMask] = maxOf(maxNs[previousMask], continuousNs)
        continuousNs = 0
    }

    fun summary(): Map<String, Any?> =
        mapOf(
            "scope" to "disjoint observed gate-mask dwell inside offering window; loop-transition resolution; wall time, not CPU",
            "bits" to mapOf("admission" to 1, "allReplicaObservation" to 2, "verifiedAckWindow" to 4),
            "elapsedNs" to totalNs.sum(),
            "masks" to
                (0..7).map { mapOf("mask" to it, "totalNs" to totalNs[it], "visits" to visits[it], "maxObservedDwellNs" to maxNs[it]) },
        )
}

internal fun haProbeGateMask(
    sent: Int,
    received: Int,
    completed: Int,
    maxInFlight: Int,
    maxExecutionInFlight: Int,
    nextRequestSequence: Long,
    confirmedThrough: Long,
    requestWindow: Long,
): Int =
    (if (sent - received >= maxInFlight) 1 else 0) or
        (if (sent - completed >= maxExecutionInFlight) 2 else 0) or
        (if (nextRequestSequence - confirmedThrough > requestWindow) 4 else 0)

/** An expired global deadline yields a zero-length gate window after array preparation. */
internal fun haProbeSendDeadline(
    phaseStartNs: Long,
    expectedWindowNs: Long?,
    stopDeadlineNs: Long?,
): Long? {
    val phaseDeadline = expectedWindowNs?.let { phaseStartNs + it }
    val deadline = if (stopDeadlineNs == null) phaseDeadline else minOf(phaseDeadline ?: stopDeadlineNs, stopDeadlineNs)
    return deadline?.let { maxOf(phaseStartNs, it) }
}

/** Only the sustained path uses quantized percentiles; short runs retain exact sorted samples. */
internal class HaProbeLatencies {
    private val admission = Histogram(3)
    private val leader = Histogram(3)
    private val replicas = Histogram(3)
    val count: Long get() = admission.totalCount

    fun record(
        admissionNs: Long,
        leaderNs: Long,
        replicasNs: Long,
    ) {
        require(admissionNs >= 0 && leaderNs >= 0 && replicasNs >= 0)
        admission.recordValue(admissionNs)
        leader.recordValue(leaderNs)
        replicas.recordValue(replicasNs)
    }

    fun percentiles(): List<Long> =
        listOf(
            admission.getValueAtPercentile(95.0),
            admission.getValueAtPercentile(99.0),
            leader.getValueAtPercentile(95.0),
            leader.getValueAtPercentile(99.0),
            replicas.getValueAtPercentile(95.0),
            replicas.getValueAtPercentile(99.0),
        )
}

private fun mergeHaProbeGateDwell(
    first: Map<String, Any?>,
    next: Map<String, Any?>,
): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val firstMasks = first["masks"] as List<Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    val nextMasks = next["masks"] as List<Map<String, Any>>
    return first +
        mapOf(
            "scope" to
                "sum of disjoint observed gate-mask dwell inside each offering window; phase preparation, aggregation and drain gaps excluded",
            "elapsedNs" to Math.addExact(first["elapsedNs"] as Long, next["elapsedNs"] as Long),
            "masks" to
                (0..7).map { mask ->
                    val previous = firstMasks[mask]
                    val current = nextMasks[mask]
                    mapOf(
                        "mask" to mask,
                        "totalNs" to Math.addExact(previous["totalNs"] as Long, current["totalNs"] as Long),
                        "visits" to Math.addExact(previous["visits"] as Long, current["visits"] as Long),
                        "maxObservedDwellNs" to maxOf(previous["maxObservedDwellNs"] as Long, current["maxObservedDwellNs"] as Long),
                    )
                },
        )
}

private data class HaProbePhase(
    val count: Int,
    val beginning: Long,
    val admissionEnd: Long,
    val completionEnd: Long,
    val replicasEnd: Long,
    val percentiles: List<Long>,
    val endNs: Long,
    val ackRequests: Int,
    val maxUnanswered: Int,
    val maxIncomplete: Int,
    val backpressuredOffers: Long,
    val sendStoppedNs: Long,
    val expectedWindowNs: Long?,
    val phaseStartNs: Long,
    val gateDwell: Map<String, Any?>,
)

/** Bounded, unpaced finite cohort. Any missing TCP frame invalidates completion measurements. */
internal fun runHaTradingProbeClient(
    wire: HaProbeWire,
    options: TradingEngineProbeOptions,
    maxInFlight: Int,
) {
    require(maxInFlight in 256..1024)
    val maxExecutionInFlight = System.getenv("HA_PROBE_EXECUTION_IN_FLIGHT")?.toInt() ?: 1_024
    require(maxExecutionInFlight in maxInFlight..4_096)
    val ackMode = System.getenv("HA_PROBE_ACK_MODE") ?: "async"
    require(ackMode == "sync" || ackMode == "async")
    val plan = HaProbeRunPlan.from(options)
    wire.configure(plan)
    val config = haTradingProbeSettings()
    Files.createDirectories(config.runRoot)
    HaProbeMarkerReceiver(System.getenv("HA_PROBE_MARKER_PORT")?.toInt() ?: 19999, maxExecutionInFlight + 64).use { markers ->
        HaProbeConnection(config).use { connection ->
            wire.initialize(connection)
            val workload = TradingEngineProbeWorkload
            wire.fund(
                connection,
                DevelopmentBalanceCommandPayload(
                    OpeningBalance(
                        "fund-buyer",
                        workload.buyer,
                        workload.quote,
                        Amount(workload.quoteFunding(plan.capacity)),
                    ),
                    "opening-buyer",
                    workload.time,
                ),
            )
            for ((ordinal, market) in workload.markets.withIndex()) {
                val amount = plan.baseFunding(ordinal)
                if (amount > 0) {
                    wire.fund(
                        connection,
                        DevelopmentBalanceCommandPayload(
                            OpeningBalance(
                                "fund-$ordinal",
                                workload.seller,
                                market.baseAssetId,
                                Amount(amount),
                            ),
                            "opening-$ordinal",
                            workload.time,
                        ),
                    )
                }
            }
            wire.open(connection)

            fun checkpoint(
                label: String,
                buyerFloor: Int,
            ) {
                val control = System.getenv("HA_PROBE_CONTROL")?.let(Path::of) ?: return
                val mapper =
                    tools.jackson.module.kotlin
                        .jacksonObjectMapper()
                val temporary = control.resolve("client-phase-$label.tmp")
                Files.writeString(
                    temporary,
                    mapper.writeValueAsString(mapOf("label" to label, "buyerAckFloor" to buyerFloor, "sellerAckFloor" to plan.sellOrders)),
                )
                Files.move(temporary, control.resolve("client-phase-$label.request.json"), StandardCopyOption.REPLACE_EXISTING)
                val ready = control.resolve("client-phase-$label.ready.json")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                while (!Files.exists(ready)) {
                    check(System.nanoTime() - deadline < 0) { "all-node CAPTURE checkpoint timed out: $label" }
                    connection.poll()
                    markers.poll()
                    Thread.sleep(1)
                }
            }

            fun phase(
                first: Int,
                count: Int,
                side: Side,
                durationSeconds: Int? = null,
                stopDeadlineNs: Long? = null,
                latencies: HaProbeLatencies? = null,
                recordMetrics: Boolean = true,
                progress: ((Long, Int, Int, Int) -> Unit)? = null,
            ): HaProbePhase {
                require(count > 0 && first >= 0)
                require(first.toLong() + count <= if (side == Side.SELL) plan.sellOrders.toLong() else plan.capacity.toLong())
                val starts = LongArray(count)
                val responses = LongArray(count)
                val sequences = LongArray(count)
                val leader = LongArray(count)
                val replicas = LongArray(count)
                var sent = 0
                var received = 0
                var prefix = 0
                var completed = 0
                var maxUnanswered = 0
                var maxIncomplete = 0
                var backpressuredOffers = 0L
                val ack = HaProbeAckCoordinator(wire, side, first.toLong())
                var frame: ByteArray? = null
                val phaseStart = System.nanoTime()
                val expectedWindowNs = durationSeconds?.let { TimeUnit.SECONDS.toNanos(it.toLong()) }
                val sendDeadline = haProbeSendDeadline(phaseStart, expectedWindowNs, stopDeadlineNs)
                val gates = HaProbeGateDwell(phaseStart, sendDeadline)
                val deadline = phaseStart + TimeUnit.SECONDS.toNanos(90)
                var sendingClosed = false
                var sendStoppedNs = 0L
                var sampleCapReached = false
                connection.onFrame = { bytes ->
                    if (!ack.accept(bytes)) {
                        wire.admission(bytes, side)?.let { result ->
                            val index = result.index - first
                            check(index in 0 until sent) { "unexpected admission response index ${result.index}" }
                            if (responses[index] == 0L) {
                                responses[index] = System.nanoTime()
                                sequences[index] = result.admissionSequence
                                received++
                                markers.register(result.admissionSequence, index)
                            } else {
                                check(sequences[index] == result.admissionSequence) { "duplicate response changed admission sequence" }
                            }
                        }
                    }
                }

                fun pollCompleted() {
                    markers.poll()
                    markers.drain { result ->
                        check(result.index in 0 until count && sequences[result.index] == result.sequence)
                        check(replicas[result.index] == 0L) { "duplicate completed response" }
                        leader[result.index] = result.leaderNs
                        replicas[result.index] = result.allReplicasNs
                        completed++
                    }
                }

                fun observeGates() {
                    if (!sendingClosed) {
                        gates.observeIfChanged(
                            haProbeGateMask(
                                sent,
                                received,
                                completed,
                                maxInFlight,
                                maxExecutionInFlight,
                                first + sent + 1L,
                                ack.confirmedThrough,
                                wire.requestWindow,
                            ),
                        )
                    }
                }
                while (!sendingClosed || received < sent || completed < sent || ack.confirmedThrough < first + sent) {
                    val loopNow = System.nanoTime()
                    progress?.invoke(loopNow, sent, received, completed)
                    check(loopNow - deadline < 0) {
                        "cohort drain timeout: sent=$sent received=$received completed=$completed ack=${ack.confirmedThrough}"
                    }
                    pollCompleted()
                    if (!sendingClosed && sendDeadline != null) {
                        val now = System.nanoTime()
                        if (now - sendDeadline >= 0) {
                            sendingClosed = true
                            sendStoppedNs = now
                        }
                    }
                    var burst = 0
                    observeGates()
                    while (!sendingClosed && sent < count && sent - received < maxInFlight && sent - completed < maxExecutionInFlight &&
                        first + sent + 1L - ack.confirmedThrough <= wire.requestWindow && burst++ < 32
                    ) {
                        if (sendDeadline != null && System.nanoTime() - sendDeadline >= 0) {
                            sendingClosed = true
                            sendStoppedNs = System.nanoTime()
                            break
                        }
                        if (frame == null) {
                            starts[sent] = System.nanoTime()
                            frame = wire.encodeOrder(first + sent, side)
                        }
                        if (stopDeadlineNs != null && System.nanoTime() - checkNotNull(sendDeadline) >= 0) {
                            sendingClosed = true
                            sendStoppedNs = System.nanoTime()
                            break
                        }
                        if (!connection.offer(checkNotNull(frame))) {
                            backpressuredOffers++
                            break
                        }
                        sent++
                        frame = null
                        maxUnanswered = maxOf(maxUnanswered, sent - received)
                        maxIncomplete = maxOf(maxIncomplete, sent - completed)
                    }
                    if (!sendingClosed && sent == count) {
                        sendingClosed = true
                        sendStoppedNs = System.nanoTime()
                        sampleCapReached = sendDeadline != null && stopDeadlineNs == null
                    }
                    if (sendingClosed) gates.close(sendStoppedNs) else observeGates()
                    connection.poll()
                    pollCompleted()
                    while (prefix < sent && responses[prefix] != 0L) prefix++
                    ack.observeThrough(first + prefix.toLong())
                    ack.pump(connection::offer, System.nanoTime(), flush = sendingClosed && received == sent)
                    observeGates()
                    if (ackMode == "sync") {
                        // Same protocol, collector and credits; only stop publishing until this ACK is confirmed.
                        while (ack.awaitingReply) {
                            connection.poll()
                            pollCompleted()
                            val ackNow = System.nanoTime()
                            progress?.invoke(ackNow, sent, received, completed)
                            ack.pump(connection::offer, ackNow, flush = sendingClosed && received == sent)
                            Thread.onSpinWait()
                        }
                    }
                    Thread.onSpinWait()
                }
                check(markers.retained == 0) { "completion collector retained uncorrelated markers" }
                check(markers.malformed == 0) { "completion measurement invalid: malformed TCP markers" }
                check(!sampleCapReached) { "duration measurement invalid: maxSamples capacity reached" }
                check(sent > 0 || stopDeadlineNs != null) { "duration measurement invalid: no order was sent" }
                val end = System.nanoTime()

                fun percentile(
                    values: LongArray,
                    fraction: Double,
                ): Long {
                    values.sort()
                    return values[(kotlin.math.ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0)]
                }
                val percentiles =
                    if (!recordMetrics) {
                        emptyList()
                    } else if (latencies != null) {
                        for (index in 0 until sent) {
                            latencies.record(
                                responses[index] - starts[index],
                                leader[index] - starts[index],
                                replicas[index] - starts[index],
                            )
                        }
                        latencies.percentiles()
                    } else {
                        val admission = LongArray(sent) { responses[it] - starts[it] }
                        val completion = LongArray(sent) { leader[it] - starts[it] }
                        val allReplicas = LongArray(sent) { replicas[it] - starts[it] }
                        listOf(
                            percentile(admission, .95),
                            percentile(admission, .99),
                            percentile(completion, .95),
                            percentile(completion, .99),
                            percentile(allReplicas, .95),
                            percentile(allReplicas, .99),
                        )
                    }
                // Every response, marker and final ACK was consumed. The callback must not retain this phase's arrays.
                if (plan.sustained) connection.onFrame = {}
                return HaProbePhase(
                    sent,
                    if (sent == 0) 0 else starts[0],
                    if (sent == 0) 0 else (0 until sent).maxOf { responses[it] },
                    if (sent == 0) 0 else (0 until sent).maxOf { leader[it] },
                    if (sent == 0) 0 else (0 until sent).maxOf { replicas[it] },
                    percentiles,
                    end,
                    ack.requests,
                    maxUnanswered,
                    maxIncomplete,
                    backpressuredOffers,
                    sendStoppedNs,
                    expectedWindowNs,
                    phaseStart,
                    gates.summary(),
                )
            }
            phase(0, plan.sellOrders, Side.SELL, recordMetrics = false)
            val warmup =
                if (plan.partialMaker) {
                    phase(0, plan.warmupCapacity, Side.BUY, plan.warmupSeconds, recordMetrics = false).count
                } else {
                    if (options.warmup != 0) phase(0, options.warmup, Side.BUY, recordMetrics = false)
                    options.warmup
                }
            checkpoint("measure-start", warmup)
            val collectorBefore = markers.summary()
            val measured =
                if (plan.sustained) {
                    // Start the wall window before the first sample arrays are allocated.
                    val began = System.nanoTime()
                    val windowNs = TimeUnit.SECONDS.toNanos(plan.measureSeconds.toLong())
                    val stopDeadline = began + windowNs
                    val intervalNs = TimeUnit.SECONDS.toNanos(10)
                    var nextInterval = began + intervalNs
                    var previousInterval = began
                    var previousOrders = 0
                    var phases = 0
                    var summary: HaProbePhase? = null
                    val latencies = HaProbeLatencies()
                    while (System.nanoTime() - stopDeadline < 0) {
                        val previous = summary
                        val measuredBefore = previous?.count ?: 0
                        val first = Math.addExact(warmup, measuredBefore)
                        check(first < plan.capacity) { "duration measurement invalid: maxSamples capacity reached" }
                        phases++
                        val current =
                            phase(
                                first,
                                minOf(1_000_000, plan.capacity - first),
                                Side.BUY,
                                10,
                                stopDeadline,
                                latencies,
                            ) { now, sent, received, completed ->
                                if (now - nextInterval >= 0) {
                                    val orders = Math.addExact(measuredBefore, received)
                                    fundingEvent(
                                        mapOf(
                                            "kind" to "HA_INTERVAL",
                                            "mode" to wire.mode,
                                            "workload" to plan.workload,
                                            "elapsedNs" to now - began,
                                            "measureSeconds" to plan.measureSeconds,
                                            "phases" to phases,
                                            "sent" to Math.addExact(measuredBefore, sent),
                                            "orders" to orders,
                                            "allReplicas" to Math.addExact(measuredBefore, completed),
                                            "intervalOrders" to orders - previousOrders,
                                            "intervalElapsedNs" to now - previousInterval,
                                            "intervalTps" to (orders - previousOrders) * 1e9 / (now - previousInterval),
                                            "averageTps" to orders * 1e9 / (now - began),
                                        ),
                                    )
                                    previousOrders = orders
                                    previousInterval = now
                                    nextInterval = began + ((now - began) / intervalNs + 1) * intervalNs
                                }
                            }
                        check(Math.addExact(first, current.count) < plan.capacity) {
                            "duration measurement invalid: maxSamples capacity reached"
                        }
                        summary =
                            if (previous == null) {
                                current.copy(phaseStartNs = began, expectedWindowNs = windowNs)
                            } else {
                                current.copy(
                                    count = Math.addExact(previous.count, current.count),
                                    beginning = if (previous.count > 0) previous.beginning else current.beginning,
                                    admissionEnd = maxOf(previous.admissionEnd, current.admissionEnd),
                                    completionEnd = maxOf(previous.completionEnd, current.completionEnd),
                                    replicasEnd = maxOf(previous.replicasEnd, current.replicasEnd),
                                    ackRequests = Math.addExact(previous.ackRequests, current.ackRequests),
                                    maxUnanswered = maxOf(previous.maxUnanswered, current.maxUnanswered),
                                    maxIncomplete = maxOf(previous.maxIncomplete, current.maxIncomplete),
                                    backpressuredOffers = Math.addExact(previous.backpressuredOffers, current.backpressuredOffers),
                                    sendStoppedNs = maxOf(previous.sendStoppedNs, current.sendStoppedNs),
                                    expectedWindowNs = windowNs,
                                    phaseStartNs = began,
                                    gateDwell = mergeHaProbeGateDwell(previous.gateDwell, current.gateDwell),
                                )
                            }
                    }
                    val result = checkNotNull(summary)
                    check(result.count > 0 && latencies.count == result.count.toLong())
                    result.copy(endNs = System.nanoTime(), sendStoppedNs = maxOf(stopDeadline, result.sendStoppedNs))
                } else if (plan.partialMaker) {
                    phase(warmup, plan.capacity - warmup, Side.BUY, plan.measureSeconds)
                } else {
                    phase(warmup, options.orders, Side.BUY)
                }
            val orderCount = measured.count
            val actualBuys = Math.addExact(warmup, orderCount)
            val collectorAfter = markers.summary()
            checkpoint("measure-end", actualBuys)

            val beginning = measured.beginning
            val admissionEnd = measured.admissionEnd
            val completionEnd = measured.completionEnd
            val replicasEnd = measured.replicasEnd

            fun throughput(observedThrough: Long): HaProbeThroughput =
                haProbeThroughput(
                    orderCount,
                    plan.partialMaker,
                    measured.phaseStartNs,
                    beginning,
                    measured.sendStoppedNs,
                    observedThrough,
                    measured.endNs,
                    includeFinalAck = plan.sustained,
                )
            val admissionRate = throughput(admissionEnd)
            val completionRate = throughput(completionEnd)
            val replicasRate = throughput(replicasEnd)
            markers.poll()
            fundingEvent(
                mapOf(
                    "kind" to "HA_PERFORMANCE",
                    "mode" to wire.mode,
                    "scope" to
                        if (plan.partialMaker) {
                            "three-node direct Aeron; sync2; duration window; partial maker; actual TCP completion observation"
                        } else {
                            "three-node direct Aeron; sync2; finite cohort; actual TCP completion observation"
                        },
                    "workload" to plan.workload,
                    "warmup" to warmup,
                    "orders" to orderCount,
                    "sellPreparation" to plan.sellOrders,
                    "maxSamples" to plan.capacity,
                    "warmupSeconds" to plan.warmupSeconds,
                    "measureSeconds" to plan.measureSeconds,
                    "actualTotalBuys" to actualBuys,
                    "expectedBuyerQuoteAvailable" to plan.remainingQuote(actualBuys),
                    "expectedMakerRemaining" to workload.markets.indices.map { plan.remainingBase(actualBuys, it) },
                    "expectedBuyerAckFloor" to actualBuys,
                    "expectedSellerAckFloor" to plan.sellOrders,
                    "maxInFlight" to maxInFlight,
                    "maxExecutionInFlight" to maxExecutionInFlight,
                    "maxObservedUnanswered" to measured.maxUnanswered,
                    "ackMode" to ackMode,
                    "maxObservedFinancialIncomplete" to measured.maxIncomplete,
                    "asyncAckRequests" to measured.ackRequests,
                    "backpressuredOffers" to measured.backpressuredOffers,
                    "completionCollectorPeakEntries" to markers.peakEntries,
                    "completionCollectorRetained" to markers.retained,
                    "completionReceiveBufferBytes" to markers.receiveBufferBytes,
                    "collectorDiagnostics" to markers.summary(),
                    "collectorCheckpoints" to mapOf("before" to collectorBefore, "after" to collectorAfter),
                    "gateDwell" to measured.gateDwell,
                    "serviceCheckpointsRequested" to (System.getenv("HA_PROBE_CONTROL") != null),
                    "throughputSemantics" to
                        if (plan.sustained) {
                            "600-second wall window begins before sample allocation; includes all phase preparation, histogram aggregation, drain gaps and final ACK"
                        } else if (plan.partialMaker) {
                            "phase start -> max(actual offering stop, last correlated observation); cohort also includes final ACK"
                        } else {
                            "first send attempt -> last correlated observation; finite cohort"
                        },
                    "rawSpanSemantics" to "first send attempt -> last correlated observation; excludes remaining offering-window time",
                    "admissionTps" to admissionRate.tps,
                    "admissionElapsedNs" to admissionRate.elapsedNs,
                    "admissionRawSpanTps" to admissionRate.rawSpanTps,
                    "admissionRawSpanNs" to admissionRate.rawSpanNs,
                    "admissionP95Ns" to measured.percentiles[0],
                    "admissionP99Ns" to measured.percentiles[1],
                    "leaderCompletionTps" to completionRate.tps,
                    "leaderCompletionElapsedNs" to completionRate.elapsedNs,
                    "leaderCompletionRawSpanTps" to completionRate.rawSpanTps,
                    "leaderCompletionRawSpanNs" to completionRate.rawSpanNs,
                    "leaderCompletionP95Ns" to measured.percentiles[2],
                    "leaderCompletionP99Ns" to measured.percentiles[3],
                    "allReplicasTps" to replicasRate.tps,
                    "allReplicasElapsedNs" to replicasRate.elapsedNs,
                    "allReplicasRawSpanTps" to replicasRate.rawSpanTps,
                    "allReplicasRawSpanNs" to replicasRate.rawSpanNs,
                    "allReplicasP95Ns" to measured.percentiles[4],
                    "allReplicasP99Ns" to measured.percentiles[5],
                    "cohortElapsedIncludingAckNs" to replicasRate.cohortElapsedNs,
                    "cohortTpsIncludingAck" to replicasRate.cohortTps,
                    "phaseElapsedIncludingAckNs" to measured.endNs - measured.phaseStartNs,
                    "offeringWindowNs" to measured.expectedWindowNs,
                    "actualOfferingWindowNs" to measured.sendStoppedNs - measured.phaseStartNs,
                    "sendStoppedNsFromFirstAttempt" to measured.sendStoppedNs - beginning,
                    "drainAfterSendNs" to measured.endNs - measured.sendStoppedNs,
                    "completionValid" to true,
                    "sampleCapReached" to false,
                    "malformedCompletionFrames" to markers.malformed,
                ),
            )
        }
    }
}
