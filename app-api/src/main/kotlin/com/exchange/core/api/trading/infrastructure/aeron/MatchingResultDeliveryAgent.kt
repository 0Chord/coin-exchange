package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultRead
import com.exchange.core.trading.MatchingResultReadStatus
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 매칭 서비스 소유 스레드가 복원과 tail replay를 마친 세대의 불변 결과만 게시한다. */
internal interface MatchingResultSource {
    fun activeResultGeneration(): Long?

    fun readResults(
        generation: Long,
        stream: MatchingResultStream,
        fromSequence: Long,
        maxEntries: Int,
        maxBytes: Long,
    ): CompletableFuture<MatchingResultRead>
}

internal data class MatchingResultDeliveryLimits(
    val maxReadEntries: Int = 8,
    val maxReadBytes: Long = 2L * 1_024 * 1_024,
    val maxInFlightEntries: Int = 8,
    val maxInFlightBytes: Long = 4L * 1_024 * 1_024,
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
        require(maxReadEntries in 1..8 && maxReadBytes in 1..2L * 1_024 * 1_024)
        require(maxInFlightEntries in 1..8 && maxInFlightBytes in 1..4L * 1_024 * 1_024)
        require(ackBatchSize in 1..16 && ackDelayNs in 1..2_000_000)
        require(attemptTimeoutNs in 1..3_000_000_000 && reconnectDelayNs in 1..50_000_000)
        require(busyMinDelayNs > 0 && busyMaxDelayNs in busyMinDelayNs..20_000_000)
        require(keepAliveNs > 0 && keepAliveNs < attemptTimeoutNs && shutdownTimeoutMs in 1..5_000)
    }

    /** 자원 생성 전에 조회 한 건과 ACK 제어 공간까지 수용 가능한지 검사한다. */
    fun requireCompatible(configuration: SplitTradingConfiguration) {
        require(maxReadBytes >= configuration.matchingLimits.maxSingleResultBytes) { "단일 정상 결과를 조회할 공간이 필요합니다" }
        val maximum =
            SplitResultDeliveryCodec.maximumResultFrameBytes(
                configuration.matchingLimits.maxSingleResultBytes,
                configuration.matchingLimits.maxResultEvents,
            )
        require(maximum <= SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
        require(Math.addExact(maximum, controlReserve(configuration)) <= maxInFlightBytes) {
            "단일 정상 결과와 정산 ACK 제어 공간을 함께 보관해야 합니다"
        }
    }

    internal fun controlReserve(configuration: SplitTradingConfiguration): Long {
        val codec = SplitResultDeliveryCodec()
        val contexts = configuration.matchingConfiguration.markets
        val ack =
            contexts.maxOf { context ->
                codec
                    .encode(
                        Long.MAX_VALUE,
                        SplitResultDeliveryCommand.RecordAck(
                            MatchingFundsSettledAck(
                                configuration.matchingConfiguration.resultStream(context.market.marketId),
                                Long.MAX_VALUE,
                                context,
                            ),
                        ),
                    ).size
            }
        val progress =
            contexts.maxOf { context ->
                codec
                    .encode(
                        Long.MAX_VALUE,
                        SplitResultDeliveryCommand.Progress(
                            configuration.matchingConfiguration.resultStream(context.market.marketId),
                            context,
                        ),
                    ).size
            }
        return 2L * codec.encode(Long.MAX_VALUE, SplitResultDeliveryCommand.DescribePeer).size + ack + progress
    }
}

/**
 * 두 client의 connect/offer/poll/close를 한 스레드가 소유한다. 결과·금융 상태를 직접 변경하지 않고,
 * 자금 로그의 정산 확인을 매칭 로그에 되돌린다. 로컬 cursor와 전송 자료는 복구 원본이 아니다.
 */
internal class MatchingResultDeliveryAgent(
    private val configuration: SplitTradingConfiguration,
    private val source: MatchingResultSource,
    fundsEndpoint: SplitOrderDeliveryEndpoint,
    matchingEndpoint: SplitOrderDeliveryEndpoint,
    private val limits: MatchingResultDeliveryLimits = configuration.resultDeliveryLimits,
    private val connectionFactory: SplitResultDeliveryConnectionFactory = AeronSplitResultDeliveryConnectionFactory,
    private val onFailure: (Throwable) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private class Peer(
        val endpoint: SplitOrderDeliveryEndpoint,
        val role: TradingServiceRole,
        val lineage: ClusterLineage,
    ) {
        var attempt: SplitResultDeliveryConnectAttempt? = null
        var connection: SplitResultDeliveryConnection? = null
        var openedAt = 0L
        var retryAt: Long? = null
        var lastKeepAlive = 0L
        var description: Frame? = null
        var verified = false
        var epoch = 0L
    }

    private class Stream(
        val identity: MatchingResultStream,
        val context: TradingMarketContext,
    ) {
        var cursor: Long? = 1
        var issued = 0L
        var confirmed = 0L
        var epoch = 0L
        var retryAt: Long? = null
        var busyDelay = 0L
        var gap: Long? = null
        var needsProgress = false
        var failure: String? = null
        var batch: List<MatchingResultEnvelope> = emptyList()
        var batchIndex = 0
        var batchNext: Long? = null

        // issued 재확인 중에도 최신 누적 ACK 한 개만 보관하고, 검증 전에는 제출하지 않는다.
        var pendingAck: MatchingFundsSettledAck? = null
        var needsIssuedRefresh = false
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
        val sequence: Long,
        val epoch: Long,
        val bytes: Int,
        val sentAt: Long,
    )

    private data class Read(
        val stream: Stream,
        val epoch: Long,
        val from: Long,
        val createdAt: Long,
        val metadataThrough: Long?,
        val result: CompletableFuture<MatchingResultRead>,
    )

    private data class Prepared(
        val stream: Stream,
        val input: MatchingResultEnvelope,
        val frame: Frame,
    )

    private data class AckSubmission(
        val stream: Stream,
        val epoch: Long,
        val ack: MatchingFundsSettledAck,
        val frame: Frame,
    )

    private data class ProgressQuery(
        val stream: Stream,
        val epoch: Long,
        val expected: Long,
        val frame: Frame,
    )

    private val codec = SplitResultDeliveryCodec()
    private val funds = Peer(fundsEndpoint, TradingServiceRole.FUNDS, configuration.fundsLineage)
    private val matching = Peer(matchingEndpoint, TradingServiceRole.MATCHING, configuration.matchingLineage)
    private val streams =
        configuration.matchingConfiguration.markets.map {
            Stream(configuration.matchingConfiguration.resultStream(it.market.marketId), it)
        }
    private val resultByteBudget = limits.maxInFlightBytes - limits.controlReserve(configuration)
    private val flights = linkedMapOf<Long, Flight>()
    private var resultBytes = 0L
    private var read: Read? = null
    private var prepared: Prepared? = null
    private var ackRequest: AckSubmission? = null
    private var progressRequest: ProgressQuery? = null
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
        require(limits == configuration.resultDeliveryLimits) { "실제 결과 전송 한도는 복구 설정에 결속한 한도와 같아야 합니다" }
        limits.requireCompatible(configuration)
        require(fundsEndpoint.clusterId != matchingEndpoint.clusterId) { "split peers must have different cluster IDs" }
        require(fundsEndpoint.driverDirectory == matchingEndpoint.driverDirectory) { "delivery clients must reuse the node driver" }
        require(fundsEndpoint.egressEndpoint != matchingEndpoint.egressEndpoint || fundsEndpoint.egressEndpoint.endsWith(":0")) {
            "delivery clients cannot bind the same response port"
        }
    }

    @Synchronized fun start(): MatchingResultDeliveryAgent {
        check(thread == null && owner == null && !stopping.get()) { "result delivery worker has already started or stopped" }
        thread =
            Thread(::run, "matching-result-delivery").also {
                it.isDaemon = true
                it.start()
            }
        return this
    }

    private fun run() {
        val idle = TradingAeronRuntime.idleStrategy()
        try {
            while (!stopping.get()) idle.idle(doWork())
            val flushNs = minOf(limits.attemptTimeoutNs, TimeUnit.MILLISECONDS.toNanos((limits.shutdownTimeoutMs - 200).coerceAtLeast(0)))
            val started = nanoTime()
            // 종료 기한 안에서 이미 받은 ACK만 환류한다. 새 결과나 조회는 만들지 않는다.
            while (!Thread.currentThread().isInterrupted && generation != null && source.activeResultGeneration() == generation &&
                nanoTime() - started < flushNs
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

    /** 완료 future는 작업 스레드에서만 꺼내며 서비스 스레드에 continuation을 붙이지 않는다. */
    internal fun doWork(): Int {
        val current = Thread.currentThread()
        if (owner == null) owner = current
        check(owner === current) { "result delivery clients have one thread owner" }
        val active = source.activeResultGeneration()
        if (active != generation) {
            clearGeneration()
            generation = active
        }
        if (active == null || stopping.get()) return 0
        val now = nanoTime()
        var work = pollPeers(now)
        if (source.activeResultGeneration() != generation) {
            clearGeneration()
            return work
        }
        if (!funds.verified || !matching.verified) return work
        if (flights.values.any { now - it.sentAt >= limits.attemptTimeoutNs } ||
            ackRequest?.let { now - it.frame.createdAt >= limits.attemptTimeoutNs } == true ||
            progressRequest?.let { now - it.frame.createdAt >= limits.attemptTimeoutNs } == true ||
            prepared?.let { now - it.frame.createdAt >= limits.attemptTimeoutNs } == true ||
            read?.let { now - it.createdAt >= limits.attemptTimeoutNs } == true
        ) {
            reconnect(now, "split result request attempt timed out")
            return work + 1
        }
        work += consumeRead(now)
        work += flushAck(now, force = false)
        work += queryProgress(now)
        work += scheduleRead(now, metadataOnly = true)
        work += offerBatch(now)
        work += scheduleRead(now, metadataOnly = false)
        return work
    }

    private fun pollPeers(now: Long): Int {
        var work = 0
        for (peer in listOf(funds, matching)) {
            if (generation == null || source.activeResultGeneration() != generation) break
            try {
                if (peer.connection == null && peer.attempt == null && peer.retryAt?.let { now - it >= 0 } != false) {
                    val epoch = ++peer.epoch
                    peer.openedAt = now
                    peer.attempt =
                        connectionFactory.begin(peer.endpoint, limits.attemptTimeoutNs) { reply ->
                            if (peer.epoch == epoch && generation != null &&
                                source.activeResultGeneration() == generation
                            ) {
                                receive(peer, reply)
                            }
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
                        peer.description = frame(SplitResultDeliveryCommand.DescribePeer, now)
                        work++
                    } else if (now - peer.openedAt >= limits.attemptTimeoutNs) {
                        reconnect(now, "split result peer connection attempt timed out")
                        return work + 1
                    }
                }
                val connection = peer.connection ?: continue
                if (connection.isClosed) {
                    reconnect(now, "split result peer connection closed")
                    return work + 1
                }
                work += connection.poll()
                if (source.activeResultGeneration() != generation || peer.connection !== connection) return work
                if (now - peer.lastKeepAlive >= limits.keepAliveNs) {
                    connection.keepAlive()
                    peer.lastKeepAlive = now
                    work++
                }
                val description = peer.description
                if (!peer.verified && description != null) {
                    if (now - description.createdAt >= limits.attemptTimeoutNs) {
                        reconnect(now, "split result peer description timed out")
                        return work + 1
                    }
                    if (!description.accepted) work += offer(peer, description, now)
                }
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                reconnect(now, "split result transport unavailable: ${error.message}")
                return work + 1
            }
        }
        return work
    }

    private fun receive(
        peer: Peer,
        reply: SplitResultDeliveryReply,
    ) {
        require(reply.role == peer.role && reply.lineage == peer.lineage && reply.topologySha256 == configuration.resultTopologySha256) {
            "split result peer role, lineage or topology does not match"
        }
        val description = peer.description
        if (!peer.verified && description?.correlation == reply.correlationId) {
            require(reply.code == "DESCRIBED") { "unexpected split result peer description: ${reply.code}" }
            if (reply.lifecycle != TradingLifecycle.RUNNING) {
                reconnect(nanoTime(), "split result peer is not initialized: ${reply.lifecycle}")
                return
            }
            peer.verified = true
            peer.description = null
            return
        }
        if (!peer.verified) return
        if (peer === matching) receiveRecordedAck(reply) else receiveFunds(reply)
    }

    private fun receiveFunds(reply: SplitResultDeliveryReply) {
        val progress = progressRequest
        if (progress != null && progress.frame.correlation == reply.correlationId) {
            receiveProgress(progress, reply)
            return
        }
        val flight = flights[reply.correlationId] ?: return
        val stream = flight.stream
        if (stream.epoch != flight.epoch || stream.failure != null) return
        when (reply.code) {
            "APPLIED", "DUPLICATE", "PRUNED" -> {
                val ack = reply.ack
                if (ack == null || ack.stream != stream.identity || ack.context != stream.context || ack.through < flight.sequence) {
                    failStream(stream, "funds result reply has an invalid cumulative ACK")
                    return
                }
                acceptOrRefreshAck(stream, ack)
                stream.busyDelay = 0
            }

            "GAP" -> {
                val expected = reply.expectedSequence
                if (expected == null || expected <= 0 || expected > flight.sequence) {
                    failStream(stream, "funds result GAP has an invalid expected sequence")
                    return
                }
                resetStream(stream, expected, nanoTime(), preserveAck = true)
                stream.gap = expected
            }

            "NOT_READY" -> {
                retryStream(stream)
            }

            else -> {
                failStream(stream, "funds result delivery stopped: ${reply.code}")
            }
        }
    }

    private fun acceptOrRefreshAck(
        stream: Stream,
        ack: MatchingFundsSettledAck,
    ) {
        if (ack.through > stream.issued) {
            // 누적 응답이 과거 read의 issued를 앞설 때 현재 서비스 상태를 한 번 더 확인한다.
            val previous = stream.pendingAck
            if (previous == null) stream.ackSince = nanoTime()
            if (previous == null || ack.through > previous.through) {
                stream.pendingAck = ack
                stream.needsIssuedRefresh = true
            }
            return
        }
        removeFlights(stream) { it.sequence <= ack.through }
        if (ack.through > stream.confirmed) {
            val previous = stream.pendingAck
            if (previous == null) stream.ackSince = nanoTime()
            if (previous == null || ack.through >= previous.through) {
                stream.pendingAck = ack
                stream.needsIssuedRefresh = false
            }
            stream.ackCount = minOf(limits.ackBatchSize, stream.ackCount + 1)
        }
    }

    private fun receiveRecordedAck(reply: SplitResultDeliveryReply) {
        val request = ackRequest ?: return
        val stream = request.stream
        if (request.frame.correlation != reply.correlationId || stream.epoch != request.epoch) return
        if (reply.code == "NOT_READY") {
            reconnect(nanoTime(), "matching result ACK peer is not ready")
            return
        }
        val ack = reply.ack
        val issued = reply.resultIssued
        if (reply.code != "ACKNOWLEDGED" || ack == null || ack.stream != stream.identity || ack.context != stream.context ||
            ack.through < request.ack.through || issued == null || issued < 0 || ack.through > issued
        ) {
            failStream(stream, "matching rejected or returned an invalid settlement ACK: ${reply.code}")
            return
        }
        stream.issued = maxOf(stream.issued, issued)
        stream.confirmed = maxOf(stream.confirmed, ack.through)
        if (stream.pendingAck?.through?.let { it <= stream.confirmed } == true) clearPendingAck(stream)
        removeFlights(stream) { it.sequence <= stream.confirmed }
        ackRequest = null
    }

    private fun receiveProgress(
        request: ProgressQuery,
        reply: SplitResultDeliveryReply,
    ) {
        val stream = request.stream
        if (request.epoch != stream.epoch) return
        progressRequest = null
        if (reply.code == "NOT_READY") {
            stream.retryAt = nanoTime() + limits.busyMinDelayNs
            return
        }
        val ack = reply.ack
        if (reply.code != "PROGRESS" || ack == null || ack.stream != stream.identity || ack.context != stream.context ||
            ack.through < request.expected || ack.through < stream.confirmed
        ) {
            failStream(stream, "funds progress is behind an already pruned matching result")
            return
        }
        stream.needsProgress = false
        stream.gap = null
        stream.cursor = after(maxOf(stream.confirmed, ack.through))
        acceptOrRefreshAck(stream, ack)
    }

    private fun consumeRead(now: Long): Int {
        val request = read ?: return 0
        if (!request.result.isDone) return 0
        read = null
        val stream = request.stream
        if (request.epoch != stream.epoch || source.activeResultGeneration() != generation) return 1
        val result =
            try {
                request.result.join()
            } catch (error: Exception) {
                failStream(stream, "source result read failed: ${error.cause?.message ?: error.message}")
                return 1
            }
        if (result.fundsSettledThrough < 0 || result.resultIssued < 0 || result.fundsSettledThrough > result.resultIssued) {
            failStream(stream, "source result read has an invalid durable prefix")
            return 1
        }
        // 조회 완료 뒤 더 최근 matching ACK를 먼저 poll할 수 있으므로 오래된 조회는 prefix를 낮추지 않는다.
        stream.confirmed = maxOf(stream.confirmed, result.fundsSettledThrough)
        stream.issued = maxOf(stream.issued, result.resultIssued)
        if (stream.pendingAck?.through?.let { it <= stream.confirmed } == true) clearPendingAck(stream)
        removeFlights(stream) { it.sequence <= stream.confirmed }
        if (request.metadataThrough != null) {
            if (result.status !in
                setOf(MatchingResultReadStatus.BATCH, MatchingResultReadStatus.PRUNED, MatchingResultReadStatus.BATCH_TOO_SMALL) ||
                result.entries.isNotEmpty()
            ) {
                failStream(stream, "source result metadata query failed: ${result.status}")
                return 1
            }
            val candidate = stream.pendingAck.takeIf { stream.needsIssuedRefresh }
            stream.needsIssuedRefresh = false
            if (candidate != null) {
                when {
                    candidate.through <= stream.issued -> acceptOrRefreshAck(stream, candidate)
                    candidate.through > request.metadataThrough -> stream.needsIssuedRefresh = true
                    else -> failStream(stream, "funds result ACK exceeds current source result issuance")
                }
            }
            return 1
        }
        when (result.status) {
            MatchingResultReadStatus.PRUNED -> {
                if (request.from > result.fundsSettledThrough || result.entries.isNotEmpty() ||
                    result.nextSequence != after(result.fundsSettledThrough)
                ) {
                    failStream(stream, "source pruned result read has an invalid cursor")
                } else if (stream.gap != null) {
                    stream.needsProgress = true
                } else {
                    stream.cursor = after(stream.confirmed)
                }
            }

            MatchingResultReadStatus.BATCH -> {
                var expected: Long? = request.from
                var bytes = 0L
                for (input in result.entries) {
                    if (input.stream != stream.identity || input.input.context != stream.context || input.resultSequence != expected ||
                        input.resultSequence > result.resultIssued
                    ) {
                        failStream(stream, "source result batch is not a continuous configured stream")
                        return 1
                    }
                    bytes = Math.addExact(bytes, input.canonicalBytes)
                    expected = after(input.resultSequence)
                }
                if (result.entries.size > limits.maxReadEntries || bytes > limits.maxReadBytes || result.nextSequence != expected) {
                    failStream(stream, "source result read exceeded its bounds or cursor contract")
                    return 1
                }
                stream.batch = result.entries.filter { it.resultSequence > stream.confirmed }
                stream.batchIndex = 0
                stream.batchNext = result.nextSequence
                stream.cursor =
                    if (stream.batch.isEmpty()) after(stream.confirmed).let { maxCursor(it, result.nextSequence) } else stream.cursor
                stream.gap = null
                if (stream.batch.isEmpty()) stream.retryAt = now + limits.busyMinDelayNs
            }

            MatchingResultReadStatus.BATCH_TOO_SMALL -> {
                failStream(stream, "result read byte limit cannot retain one source result: requiredBytes=${result.requiredBytes}")
            }

            else -> {
                failStream(stream, "source result query failed: ${result.status}")
            }
        }
        return 1
    }

    private fun scheduleRead(
        now: Long,
        metadataOnly: Boolean,
    ): Int {
        if (read != null) return 0
        val candidate = streams.firstOrNull { it.failure == null && it.needsIssuedRefresh }
        if (candidate != null) {
            // 1byte로 읽으면 정상 결과 본문은 반환하지 않고 현재 발급·회수 경계만 확인할 수 있다.
            val future = source.readResults(checkNotNull(generation), candidate.identity, 1, 1, 1)
            read = Read(candidate, candidate.epoch, 1, now, checkNotNull(candidate.pendingAck).through, future)
            return 1
        }
        if (metadataOnly || prepared != null || streams.any { it.batchIndex < it.batch.size } ||
            flights.size >= limits.maxInFlightEntries
        ) {
            return 0
        }
        repeat(streams.size) {
            val stream = streams[nextStream]
            nextStream = (nextStream + 1) % streams.size
            val cursor = stream.cursor
            if (stream.failure == null && cursor != null && !stream.needsProgress && stream.retryAt?.let { now - it >= 0 } != false) {
                val future =
                    source.readResults(checkNotNull(generation), stream.identity, cursor, limits.maxReadEntries, limits.maxReadBytes)
                read = Read(stream, stream.epoch, cursor, now, null, future)
                return 1
            }
        }
        return 0
    }

    private fun offerBatch(now: Long): Int {
        var work = 0
        // 한 번에 최대8건만 발행한 뒤 polling과 ACK 제어 경로로 돌아간다.
        repeat(limits.maxInFlightEntries) {
            if (source.activeResultGeneration() != generation || !funds.verified || !matching.verified) return work
            if (flights.size + (if (prepared == null) 0 else 1) > limits.maxInFlightEntries) return work
            var item = prepared
            if (item == null) {
                if (flights.size >= limits.maxInFlightEntries) return work
                val stream =
                    streams.firstOrNull { it.failure == null && !it.needsIssuedRefresh && it.batchIndex < it.batch.size } ?: return work
                val input = stream.batch[stream.batchIndex]
                val maximum = SplitResultDeliveryCodec.maximumResultFrameBytes(input.canonicalBytes, maxOf(1, input.eventCount))
                // encode로 준비 frame을 만들기 전부터 예산을 확보해 일시적인 초과 보관도 막는다.
                if (maximum > resultByteBudget - resultBytes) return work
                val next = frame(SplitResultDeliveryCommand.Apply(input), now)
                if (next.bytes.size > resultByteBudget - resultBytes) return work
                item = Prepared(stream, input, next)
                prepared = item
                resultBytes += next.bytes.size
            }
            val offered = offer(funds, item.frame, now)
            work += offered
            if (!item.frame.accepted || prepared !== item) return work
            flights[item.frame.correlation] = Flight(item.stream, item.input.resultSequence, item.stream.epoch, item.frame.bytes.size, now)
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
        if (!funds.verified || !matching.verified || source.activeResultGeneration() != generation) return 0
        var request = ackRequest
        if (request == null) {
            val stream =
                streams.firstOrNull {
                    it.failure == null && it.pendingAck != null && !it.needsIssuedRefresh && (
                        force || it.ackCount >= limits.ackBatchSize || now - it.ackSince >= limits.ackDelayNs ||
                            (flights.isEmpty() && it.batchIndex == it.batch.size)
                    )
                } ?: return 0
            val ack = checkNotNull(stream.pendingAck)
            request = AckSubmission(stream, stream.epoch, ack, frame(SplitResultDeliveryCommand.RecordAck(ack), now))
            ackRequest = request
        }
        return if (request.frame.accepted) 0 else offer(matching, request.frame, now)
    }

    private fun queryProgress(now: Long): Int {
        var request = progressRequest
        if (request == null) {
            val stream =
                streams.firstOrNull {
                    it.failure == null && it.needsProgress && it.retryAt?.let { retry -> now - retry >= 0 } != false
                } ?: return 0
            request =
                ProgressQuery(
                    stream,
                    stream.epoch,
                    checkNotNull(stream.gap),
                    frame(SplitResultDeliveryCommand.Progress(stream.identity, stream.context), now),
                )
            progressRequest = request
        }
        return if (request.frame.accepted) 0 else offer(funds, request.frame, now)
    }

    private fun retryStream(stream: Stream) {
        val delay = if (stream.busyDelay == 0L) limits.busyMinDelayNs else minOf(limits.busyMaxDelayNs, stream.busyDelay * 2)
        resetStream(stream, after(stream.confirmed), nanoTime() + delay, preserveAck = true)
        stream.busyDelay = delay
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
                reconnect(now, "split result publication unavailable: $result")
            }

            result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION -> {
                throw IllegalStateException("unexpected split result publication result: $result")
            }
        }
        return if (result > 0) 1 else 0
    }

    private fun frame(
        command: SplitResultDeliveryCommand,
        now: Long,
    ): Frame {
        check(nextCorrelation < Long.MAX_VALUE) { "split result correlation sequence exhausted" }
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
                resultBytes -= entry.value.bytes
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
        val ack =
            if (preserveAck) {
                listOfNotNull(
                    stream.pendingAck.takeUnless { stream.needsIssuedRefresh },
                    ackRequest?.takeIf { it.stream === stream }?.ack,
                ).maxByOrNull { it.through }
            } else {
                null
            }
        stream.epoch++
        removeFlights(stream)
        if (read?.stream === stream) {
            read?.result?.cancel(false)
            read = null
        }
        if (prepared?.stream === stream) {
            resultBytes -= checkNotNull(prepared).frame.bytes.size
            prepared = null
        }
        if (ackRequest?.stream === stream) ackRequest = null
        if (progressRequest?.stream === stream) progressRequest = null
        stream.batch = emptyList()
        stream.batchIndex = 0
        stream.cursor = cursor
        stream.retryAt = retryAt
        stream.gap = null
        stream.needsProgress = false
        clearPendingAck(stream)
        if (ack != null && ack.through > stream.confirmed) {
            stream.pendingAck = ack
            stream.ackCount = limits.ackBatchSize
            stream.ackSince = nanoTime()
        }
    }

    private fun clearPendingAck(stream: Stream) {
        stream.pendingAck = null
        stream.needsIssuedRefresh = false
        stream.ackCount = 0
        stream.ackSince = 0
    }

    private fun failStream(
        stream: Stream,
        reason: String,
    ) {
        resetStream(stream, null, null)
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
        for (stream in streams) {
            if (stream.failure == null) resetStream(stream, 1, now + limits.reconnectDelayNs)
        }
        funds.retryAt = now + limits.reconnectDelayNs
        matching.retryAt = now + limits.reconnectDelayNs
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
        resultBytes = 0
        // nanoTime의 음수 원점도 유효하므로 0을 재시도 sentinel로 쓰지 않는다.
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
            check(owner == null || owner === Thread.currentThread()) { "only the result delivery owner can close its clients" }
            clearGeneration()
        } else if (worker !== Thread.currentThread()) {
            val firstWaitMs = (limits.shutdownTimeoutMs - 100).coerceAtLeast(1)
            worker.join(firstWaitMs)
            if (worker.isAlive) {
                worker.interrupt()
                val remainingMs = limits.shutdownTimeoutMs - firstWaitMs
                if (remainingMs > 0) worker.join(remainingMs)
            }
            check(!worker.isAlive) { "result delivery worker did not stop within its shutdown bound" }
        }
    }

    private fun after(sequence: Long): Long? = if (sequence == Long.MAX_VALUE) null else sequence + 1

    private fun maxCursor(
        left: Long?,
        right: Long?,
    ): Long? = if (left == null || right == null) null else maxOf(left, right)
}
