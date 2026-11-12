package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchRead
import com.exchange.core.trading.FundsDispatchReadStatus
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 가변 업무 상태를 노출하지 않는 서비스 소유 스레드의 제한 조회 입구다. */
internal interface FundsDispatchSource {
    fun activeGeneration(): Long?

    fun readDispatch(
        generation: Long,
        stream: FundsDispatchStream,
        fromSequence: Long,
        maxEntries: Int,
        maxBytes: Long,
    ): CompletableFuture<FundsDispatchRead>
}

internal data class FundsOrderDeliveryLimits(
    val maxReadEntries: Int = 64,
    val maxReadBytes: Long = 256L * 1_024,
    val maxInFlightEntries: Int = 64,
    val maxInFlightBytes: Long = 512L * 1_024,
    val ackBatchSize: Int = 16,
    val ackDelayNs: Long = 2_000_000,
    val attemptTimeoutNs: Long = 3_000_000_000,
    val reconnectDelayNs: Long = 50_000_000,
    val busyMinDelayNs: Long = 1_000_000,
    val busyMaxDelayNs: Long = 20_000_000,
    val keepAliveNs: Long = 500_000_000,
    val shutdownTimeoutMs: Long = 5_000,
) {
    init {
        require(maxReadEntries in 1..64 && maxReadBytes in 1..256L * 1_024)
        require(maxInFlightEntries in 1..64 && maxInFlightBytes in SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES.toLong()..512L * 1_024)
        require(ackBatchSize in 1..16 && ackDelayNs in 1..2_000_000)
        require(attemptTimeoutNs in 1..3_000_000_000 && reconnectDelayNs in 1..50_000_000)
        require(busyMinDelayNs > 0 && busyMaxDelayNs in busyMinDelayNs..20_000_000)
        require(keepAliveNs > 0 && keepAliveNs < attemptTimeoutNs && shutdownTimeoutMs in 1..5_000)
    }
}

/**
 * 두 client와 연결 시도를 한 스레드가 소유한다. 복구 원본은 항상 자금 로그에 있고,
 * 이 객체의 cursor·batch·correlation은 세대 변경이나 재연결 때 버릴 수 있다.
 */
internal class FundsOrderDeliveryAgent(
    private val configuration: SplitTradingConfiguration,
    private val source: FundsDispatchSource,
    fundsEndpoint: SplitOrderDeliveryEndpoint,
    matchingEndpoint: SplitOrderDeliveryEndpoint,
    private val limits: FundsOrderDeliveryLimits = configuration.deliveryLimits,
    private val connectionFactory: SplitOrderDeliveryConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
    private val onFailure: (Throwable) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private class Peer(
        val endpoint: SplitOrderDeliveryEndpoint,
        val role: TradingServiceRole,
        val lineage: ClusterLineage,
    ) {
        var attempt: SplitOrderDeliveryConnectAttempt? = null
        var connection: SplitOrderDeliveryConnection? = null
        var openedAt = 0L
        var retryAt: Long? = null
        var lastKeepAlive = 0L
        var description: Frame? = null
        var verified = false
        var epoch = 0L
    }

    private class Stream(
        val identity: FundsDispatchStream,
    ) {
        var cursor: Long? = 1
        var issued = 0L
        var confirmed = 0L
        var epoch = 0L
        var retryAt: Long? = null
        var busyDelay = 0L
        var gapRecovery = false
        var failure: String? = null
        var batch: List<FundsOrderDispatch> = emptyList()
        var batchIndex = 0
        var batchNext: Long? = null
        var pendingAck: FundsMatchingAppliedAck? = null
        var ackCount = 0
        var ackSince = 0L
    }

    private data class Frame(
        val correlation: Long,
        val bytes: ByteArray,
        val createdAt: Long,
        var accepted: Boolean = false,
    )

    private data class Flight(
        val stream: Stream,
        val input: FundsOrderDispatch,
        val epoch: Long,
        val bytes: Int,
        val sentAt: Long,
    )

    private data class Read(
        val stream: Stream,
        val epoch: Long,
        val from: Long,
        val createdAt: Long,
        val result: CompletableFuture<FundsDispatchRead>,
    )

    private data class Prepared(
        val stream: Stream,
        val input: FundsOrderDispatch,
        val frame: Frame,
    )

    private data class AckSubmission(
        val stream: Stream,
        val epoch: Long,
        val ack: FundsMatchingAppliedAck,
        val frame: Frame,
    )

    private val codec = SplitOrderDeliveryCodec(configuration.inputContractVersion)
    private val funds = Peer(fundsEndpoint, TradingServiceRole.FUNDS, configuration.fundsLineage)
    private val matching = Peer(matchingEndpoint, TradingServiceRole.MATCHING, configuration.matchingLineage)
    private val streams =
        configuration.dispatchConfiguration.manifest.markets.map {
            Stream(configuration.dispatchConfiguration.stream(it.market.marketId))
        }
    private val flights = linkedMapOf<Long, Flight>()
    private var inFlightBytes = 0L
    private var read: Read? = null
    private var prepared: Prepared? = null
    private var ackRequest: AckSubmission? = null
    private var generation: Long? = null
    private var nextCorrelation = 1L
    private var nextStream = 0
    private var thread: Thread? = null
    private var owner: Thread? = null
    private val stopping = AtomicBoolean()
    private val failureRef = AtomicReference<Throwable?>()
    val failure: Throwable? get() = failureRef.get()

    @Volatile var diagnostic: String? = null
        private set

    init {
        require(limits == configuration.deliveryLimits) { "실제 전송 한도는 복구 설정에 결속한 한도와 같아야 합니다" }
        require(fundsEndpoint.clusterId != matchingEndpoint.clusterId) { "split peers must have different cluster IDs" }
        require(fundsEndpoint.driverDirectory == matchingEndpoint.driverDirectory) { "delivery clients must reuse the node driver" }
        require(fundsEndpoint.egressEndpoint != matchingEndpoint.egressEndpoint || fundsEndpoint.egressEndpoint.endsWith(":0")) {
            "delivery clients cannot bind the same response port"
        }
    }

    @Synchronized fun start(): FundsOrderDeliveryAgent {
        check(thread == null && owner == null && !stopping.get()) { "delivery worker has already started or stopped" }
        thread =
            Thread(::run, "funds-order-delivery").also {
                it.isDaemon = true
                it.start()
            }
        return this
    }

    private fun run() {
        val idle = TradingAeronRuntime.idleStrategy()
        try {
            while (!stopping.get()) idle.idle(doWork())
            // 종료 중 새 Apply는 만들지 않는다. 이미 받은 ACK는 가능한 즉시 ingress에 보낸다.
            val flushNs = minOf(limits.attemptTimeoutNs, TimeUnit.MILLISECONDS.toNanos((limits.shutdownTimeoutMs - 200).coerceAtLeast(0)))
            val deadline = nanoTime() + flushNs
            while (!Thread.currentThread().isInterrupted && generation != null && source.activeGeneration() == generation &&
                nanoTime() - deadline < 0
            ) {
                if (!funds.verified || !matching.verified) break
                pollPeers(nanoTime())
                flushAck(nanoTime(), force = true)
                if (ackRequest == null && streams.all { it.pendingAck == null }) break
                idle.idle()
            }
        } catch (error: Throwable) {
            report(error)
        } finally {
            try {
                clearGeneration()
            } catch (error: Throwable) {
                report(error)
            }
        }
    }

    /** future의 완료 값을 여기서만 꺼낸다. 서비스 스레드에서 실행할 continuation을 붙이지 않는다. */
    internal fun doWork(): Int {
        val current = Thread.currentThread()
        if (owner == null) owner = current
        check(owner === current) { "delivery clients have one thread owner" }
        val active = source.activeGeneration()
        if (active != generation) {
            clearGeneration()
            generation = active
        }
        if (active == null || stopping.get()) return 0
        val now = nanoTime()
        var work = pollPeers(now)
        if (source.activeGeneration() != generation) {
            clearGeneration()
            return work
        }
        if (!funds.verified || !matching.verified) return work
        if (flights.values.any { now - it.sentAt >= limits.attemptTimeoutNs } ||
            ackRequest?.let { now - it.frame.createdAt >= limits.attemptTimeoutNs } == true ||
            prepared?.let { now - it.frame.createdAt >= limits.attemptTimeoutNs } == true ||
            read?.let { now - it.createdAt >= limits.attemptTimeoutNs } == true
        ) {
            reconnect(now, "split request attempt timed out")
            return work + 1
        }
        work += consumeRead(now)
        work += flushAck(now, force = false)
        work += offerBatch(now)
        work += scheduleRead(now)
        return work
    }

    private fun pollPeers(now: Long): Int {
        var work = 0
        for (peer in listOf(funds, matching)) {
            if (generation == null || source.activeGeneration() != generation) break
            try {
                if (peer.connection == null && peer.attempt == null && peer.retryAt?.let { now - it >= 0 } != false) {
                    val epoch = ++peer.epoch
                    peer.openedAt = now
                    peer.attempt =
                        connectionFactory.begin(peer.endpoint, limits.attemptTimeoutNs) { reply ->
                            if (peer.epoch == epoch && source.activeGeneration() == generation && generation != null) receive(peer, reply)
                        }
                    work++
                }
                val attempt = peer.attempt
                if (attempt != null) {
                    val connection = attempt.poll()
                    if (connection != null) {
                        peer.connection = connection
                        peer.attempt = null
                        attempt.close()
                        peer.lastKeepAlive = now
                        peer.description = frame(SplitOrderDeliveryCommand.DescribePeer, now)
                        work++
                    } else if (now - peer.openedAt >= limits.attemptTimeoutNs) {
                        reconnect(now, "split peer connection attempt timed out")
                        return work + 1
                    }
                }
                val connection = peer.connection ?: continue
                if (connection.isClosed) {
                    reconnect(now, "split peer connection closed")
                    return work + 1
                }
                work += connection.poll()
                if (source.activeGeneration() != generation) return work
                if (now - peer.lastKeepAlive >= limits.keepAliveNs) {
                    connection.keepAlive()
                    peer.lastKeepAlive = now
                    work++
                }
                val description = peer.description
                if (!peer.verified && description != null) {
                    if (now - description.createdAt >= limits.attemptTimeoutNs) {
                        reconnect(now, "split peer description timed out")
                        return work + 1
                    }
                    if (!description.accepted) work += offer(peer, description, now)
                }
            } catch (error: IllegalArgumentException) {
                // 잘못된 frame이나 고정 peer 정보는 재연결로 정상 응답처럼 바꾸지 않는다.
                throw error
            } catch (error: Exception) {
                reconnect(now, "split transport unavailable: ${error.message}")
                return work + 1
            }
        }
        return work
    }

    private fun receive(
        peer: Peer,
        reply: SplitOrderDeliveryReply,
    ) {
        require(reply.role == peer.role && reply.lineage == peer.lineage && reply.topologySha256 == configuration.topologySha256) {
            "split peer role, lineage or topology does not match"
        }
        val description = peer.description
        if (!peer.verified && description?.correlation == reply.correlationId) {
            require(reply.code == "DESCRIBED") { "unexpected split peer description: ${reply.code}" }
            if (reply.lifecycle != "RUNNING") {
                reconnect(nanoTime(), "split peer is not initialized: ${reply.lifecycle}")
                return
            }
            peer.verified = true
            peer.description = null
            return
        }
        if (!peer.verified) return
        if (peer === funds) receiveSourceAck(reply) else receiveMatching(reply)
    }

    private fun receiveMatching(reply: SplitOrderDeliveryReply) {
        val flight = flights[reply.correlationId] ?: return
        val stream = flight.stream
        if (stream.epoch != flight.epoch || stream.failure != null) return
        when (reply.code) {
            "APPLIED", "DUPLICATE", "PRUNED" -> {
                val ack = reply.ack
                if (ack == null || ack.stream != stream.identity || ack.context != flight.input.context ||
                    ack.through < flight.input.dispatchSequence || ack.through > stream.issued
                ) {
                    failStream(stream, "matching reply has an invalid cumulative ACK")
                    return
                }
                // 응답 correlation보다 큰 누적 prefix도 source의 issued 안이면 유효하다.
                removeFlights(stream) { it.input.dispatchSequence <= ack.through }
                if (ack.through > stream.confirmed) {
                    val previous = stream.pendingAck
                    if (previous == null) stream.ackSince = nanoTime()
                    if (previous == null || ack.through > previous.through) stream.pendingAck = ack
                    stream.ackCount = minOf(limits.ackBatchSize, stream.ackCount + 1)
                }
                stream.busyDelay = 0
            }

            "BUSY" -> {
                val delay = if (stream.busyDelay == 0L) limits.busyMinDelayNs else minOf(limits.busyMaxDelayNs, stream.busyDelay * 2)
                resetStream(stream, after(stream.confirmed), nanoTime() + delay, preserveAck = true)
                stream.busyDelay = delay
            }

            "GAP" -> {
                val expected = reply.expectedSequence
                if (expected == null || expected <= stream.confirmed || expected > stream.issued) {
                    failStream(stream, "matching GAP conflicts with the durable source prefix")
                    return
                }
                resetStream(stream, expected, nanoTime())
                stream.gapRecovery = true
            }

            else -> {
                failStream(stream, "matching delivery stopped: ${reply.code}")
            }
        }
    }

    private fun receiveSourceAck(reply: SplitOrderDeliveryReply) {
        val request = ackRequest ?: return
        if (request.frame.correlation != reply.correlationId || request.stream.epoch != request.epoch) return
        val stream = request.stream
        val ack = reply.ack
        if (reply.code != "ACKNOWLEDGED" || ack == null || ack.stream != stream.identity || ack.context != request.ack.context ||
            ack.through < request.ack.through || ack.through > stream.issued
        ) {
            failStream(stream, "source rejected or returned an invalid matching ACK: ${reply.code}")
            return
        }
        stream.confirmed = maxOf(stream.confirmed, ack.through)
        if (stream.pendingAck?.through?.let { it <= stream.confirmed } == true) clearPendingAck(stream)
        inFlightBytes -= request.frame.bytes.size
        ackRequest = null
    }

    private fun consumeRead(now: Long): Int {
        val request = read ?: return 0
        if (!request.result.isDone) return 0
        read = null
        val stream = request.stream
        if (request.epoch != stream.epoch || source.activeGeneration() != generation) return 1
        val result =
            try {
                request.result.join()
            } catch (error: Exception) {
                failStream(stream, "source dispatch read failed: ${error.cause?.message ?: error.message}")
                return 1
            }
        if (result.matchingAppliedThrough > result.issued || result.issued < stream.issued) {
            failStream(stream, "source dispatch read moved its durable prefix backwards")
            return 1
        }
        // future 완료 뒤 더 최근 ACK 응답을 poll했을 수 있다. 오래된 순수 조회는 경계를 낮추지 않는다.
        stream.confirmed = maxOf(stream.confirmed, result.matchingAppliedThrough)
        stream.issued = result.issued
        if (stream.pendingAck?.through?.let { it <= stream.confirmed } == true) clearPendingAck(stream)
        if (!stream.gapRecovery && request.from <= stream.confirmed && result.status == FundsDispatchReadStatus.BATCH) {
            stream.cursor = after(stream.confirmed)
            return 1
        }
        when (result.status) {
            FundsDispatchReadStatus.PRUNED -> {
                if (stream.gapRecovery) {
                    failStream(stream, "matching requires source payload that was already pruned")
                } else {
                    stream.cursor = after(stream.confirmed)
                }
            }

            FundsDispatchReadStatus.BATCH -> {
                var expected: Long? = request.from
                var bytes = 0L
                for (input in result.entries) {
                    if (input.stream != stream.identity || input.dispatchSequence != expected || input.dispatchSequence > stream.issued) {
                        failStream(stream, "source dispatch batch is not a continuous configured stream")
                        return 1
                    }
                    bytes = Math.addExact(bytes, input.dispatchCanonicalBytes)
                    expected = after(input.dispatchSequence)
                }
                if (result.entries.size > limits.maxReadEntries || bytes > limits.maxReadBytes ||
                    result.nextSequence != expected
                ) {
                    failStream(stream, "source dispatch read exceeded its bounds or cursor contract")
                    return 1
                }
                stream.batch = result.entries
                stream.batchIndex = 0
                stream.batchNext = result.nextSequence
                stream.gapRecovery = false
                if (result.entries.isEmpty()) stream.retryAt = now + limits.busyMinDelayNs
            }

            FundsDispatchReadStatus.BATCH_TOO_SMALL -> {
                failStream(stream, "dispatch read byte limit cannot retain one source order: requiredBytes=${result.requiredBytes}")
            }

            else -> {
                failStream(stream, "source dispatch query failed: ${result.status}")
            }
        }
        return 1
    }

    private fun scheduleRead(now: Long): Int {
        if (read != null || prepared != null || streams.any { it.batchIndex < it.batch.size } ||
            flights.size + (if (ackRequest == null) 0 else 1) >= limits.maxInFlightEntries
        ) {
            return 0
        }
        repeat(streams.size) {
            val stream = streams[nextStream]
            nextStream = (nextStream + 1) % streams.size
            val cursor = stream.cursor
            if (stream.failure == null && cursor != null && stream.retryAt?.let { now - it >= 0 } != false) {
                val future =
                    source.readDispatch(
                        checkNotNull(generation),
                        stream.identity,
                        cursor,
                        limits.maxReadEntries,
                        limits.maxReadBytes,
                    )
                read = Read(stream, stream.epoch, cursor, now, future)
                return 1
            }
        }
        return 0
    }

    private fun offerBatch(now: Long): Int {
        var work = 0
        // 한 duty cycle의 최대64건 뒤에는 반드시 두 egress로 돌아간다.
        repeat(limits.maxInFlightEntries) {
            if (source.activeGeneration() != generation || !matching.verified || !funds.verified) return work
            if (flights.size + (if (ackRequest == null) 0 else 1) >= limits.maxInFlightEntries) return work
            var item = prepared
            if (item == null) {
                val stream = streams.firstOrNull { it.failure == null && it.batchIndex < it.batch.size } ?: return work
                val input = stream.batch[stream.batchIndex]
                item = Prepared(stream, input, frame(SplitOrderDeliveryCommand.Apply(input), now))
                prepared = item
            }
            val frame = item.frame
            if (frame.bytes.size > limits.maxInFlightBytes - inFlightBytes) return work
            val offered = offer(matching, frame, now)
            work += offered
            if (!frame.accepted || prepared !== item) return work
            flights[frame.correlation] = Flight(item.stream, item.input, item.stream.epoch, frame.bytes.size, now)
            inFlightBytes += frame.bytes.size
            item.stream.batchIndex++
            if (item.stream.batchIndex == item.stream.batch.size) {
                item.stream.cursor = item.stream.batchNext
                item.stream.batch = emptyList()
                item.stream.batchIndex = 0
            }
            prepared = null
        }
        return work
    }

    private fun flushAck(
        now: Long,
        force: Boolean,
    ): Int {
        if (!funds.verified || !matching.verified || source.activeGeneration() != generation) return 0
        var request = ackRequest
        if (request == null) {
            val stream =
                streams.firstOrNull {
                    it.failure == null && it.pendingAck != null && (
                        force || it.ackCount >= limits.ackBatchSize ||
                            now - it.ackSince >= limits.ackDelayNs || (flights.isEmpty() && it.batchIndex == it.batch.size)
                    )
                } ?: return 0
            val ack = checkNotNull(stream.pendingAck)
            request = AckSubmission(stream, stream.epoch, ack, frame(SplitOrderDeliveryCommand.RecordAck(ack), now))
            ackRequest = request
        }
        if (request.frame.accepted) return 0
        if (flights.size >= limits.maxInFlightEntries || request.frame.bytes.size > limits.maxInFlightBytes - inFlightBytes) return 0
        val work = offer(funds, request.frame, now)
        if (request.frame.accepted && ackRequest === request) inFlightBytes += request.frame.bytes.size
        return work
    }

    private fun offer(
        peer: Peer,
        frame: Frame,
        now: Long,
    ): Int {
        val result = peer.connection?.offer(frame.bytes) ?: return 0
        when {
            result > 0 -> {
                frame.accepted = true
            }

            result == Publication.NOT_CONNECTED || result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED -> {
                reconnect(now, "split publication unavailable: $result")
            }

            result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION -> {
                throw IllegalStateException("unexpected split publication result: $result")
            }
        }
        return if (result > 0) 1 else 0
    }

    private fun frame(
        command: SplitOrderDeliveryCommand,
        now: Long,
    ): Frame {
        check(nextCorrelation < Long.MAX_VALUE) { "split correlation sequence exhausted" }
        val correlation = nextCorrelation++
        return Frame(correlation, codec.encode(correlation, command), now)
    }

    private fun removeFlights(
        stream: Stream,
        predicate: (Flight) -> Boolean = { true },
    ) {
        val iterator = flights.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.stream === stream && predicate(entry.value)) {
                inFlightBytes -= entry.value.bytes
                iterator.remove()
            }
        }
    }

    private fun resetStream(
        stream: Stream,
        cursor: Long?,
        retryAt: Long?,
        preserveAck: Boolean = false,
    ) {
        val confirmedMatchingAck =
            if (preserveAck) {
                listOfNotNull(stream.pendingAck, ackRequest?.takeIf { it.stream === stream }?.ack).maxByOrNull { it.through }
            } else {
                null
            }
        stream.epoch++
        removeFlights(stream)
        if (read?.stream === stream) {
            read?.result?.cancel(false)
            read = null
        }
        if (prepared?.stream === stream) prepared = null
        if (ackRequest?.stream === stream) {
            ackRequest?.takeIf { it.frame.accepted }?.let { inFlightBytes -= it.frame.bytes.size }
            ackRequest = null
        }
        stream.batch = emptyList()
        stream.batchIndex = 0
        stream.cursor = cursor
        stream.retryAt = retryAt
        stream.gapRecovery = false
        clearPendingAck(stream)
        // BUSY는 앞서 확인한 정상 적용 prefix를 무효화하지 않는다. 재시도와 ACK 환류를 함께 진행한다.
        if (confirmedMatchingAck != null && confirmedMatchingAck.through > stream.confirmed) {
            stream.pendingAck = confirmedMatchingAck
            stream.ackCount = limits.ackBatchSize
            stream.ackSince = nanoTime()
        }
    }

    private fun clearPendingAck(stream: Stream) {
        stream.pendingAck = null
        stream.ackCount = 0
        stream.ackSince = 0
    }

    private fun failStream(
        stream: Stream,
        reason: String,
    ) {
        resetStream(stream, null, 0)
        stream.failure = reason
        diagnostic = reason
        report(IllegalStateException(reason))
    }

    private fun reconnect(
        now: Long,
        reason: String,
    ) {
        diagnostic = reason
        closePeers()
        for (stream in streams) resetStream(stream, 1, now + limits.reconnectDelayNs)
        funds.retryAt = now + limits.reconnectDelayNs
        matching.retryAt = now + limits.reconnectDelayNs
        // 기존 read에서 관측한 경계도 다시 원본에서 읽는다. 영구 업무 ID는 바꾸지 않는다.
    }

    private fun closePeers() {
        var failure: Throwable? = null
        for (peer in listOf(funds, matching)) {
            peer.epoch++
            for (resource in listOf(peer.attempt, peer.connection)) {
                try {
                    resource?.close()
                } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error
                    if (previous != null && previous !== error) previous.addSuppressed(error)
                }
            }
            peer.attempt = null
            peer.connection = null
            peer.description = null
            peer.verified = false
        }
        failure?.let { throw it }
    }

    private fun clearGeneration() {
        generation = null
        for (stream in streams) {
            resetStream(stream, 1, null)
            stream.confirmed = 0
            stream.issued = 0
            stream.busyDelay = 0
            stream.failure = null
        }
        flights.clear()
        inFlightBytes = 0
        // nanoTime은 음수 원점도 유효하므로 0을 '즉시 재시도'의 sentinel로 쓰지 않는다.
        funds.retryAt = null
        matching.retryAt = null
        closePeers()
    }

    private fun report(error: Throwable) {
        if (failureRef.compareAndSet(null, error)) onFailure(error)
    }

    override fun close() {
        stopping.set(true)
        val worker = thread
        if (worker == null) {
            check(owner == null || owner === Thread.currentThread()) { "only the delivery owner can close its clients" }
            clearGeneration()
        } else if (worker !== Thread.currentThread()) {
            val firstWaitMs = (limits.shutdownTimeoutMs - 100).coerceAtLeast(1)
            worker.join(firstWaitMs)
            if (worker.isAlive) {
                worker.interrupt()
                val remainingMs = limits.shutdownTimeoutMs - firstWaitMs
                if (remainingMs > 0) worker.join(remainingMs)
            }
            check(!worker.isAlive) { "delivery worker did not stop within its shutdown bound" }
        }
    }

    private fun after(sequence: Long): Long? = if (sequence == Long.MAX_VALUE) null else sequence + 1
}
