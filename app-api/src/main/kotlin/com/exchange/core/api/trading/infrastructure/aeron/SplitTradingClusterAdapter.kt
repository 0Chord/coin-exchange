package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedMarketProgress
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryReply
import com.exchange.core.api.trading.infrastructure.history.FundsRoleSnapshotCodec
import com.exchange.core.api.trading.infrastructure.history.MatchingRoleSnapshotCodec
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.FundsDispatchRead
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingResultAckStatus
import com.exchange.core.trading.MatchingResultRead
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingCapacityException
import com.exchange.core.trading.TradingFundingInputException
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingServiceRole
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.codecs.CloseReason
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.UnsafeBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 분리 개발 실행의 확정 callback만 역할별 상태를 변경한다. 전송 작업은 이 소유자에 접근하지 않는다. */
internal class SplitTradingClusterAdapter(
    val role: TradingServiceRole,
    private val configuration: SplitTradingConfiguration,
    private val onFatal: (Throwable) -> Unit = {},
    private val resultDeliveryEnabled: Boolean = false,
) : ClusteredService,
    FundsDispatchSource,
    MatchingResultSource {
    // 시험의 callback 관찰 wrapper도 이 실제 소유자를 관찰한다. 운영 전체 상태 조회 입구는 두지 않는다.
    private val funds: BoundedFundingState? = if (role == TradingServiceRole.FUNDS) configuration.funds() else null
    private val matching: ReservedMatchingState? = if (role == TradingServiceRole.MATCHING) configuration.matching() else null
    private val codec = SplitOrderDeliveryCodec()
    private val resultCodec = SplitResultDeliveryCodec()
    private val settlementCalculator = configuration.settlementCalculator()
    private lateinit var cluster: Cluster
    private lateinit var serviceThread: Thread
    private var liveLeader = false
    private var snapshotInProgress = false
    private var generationCounter = 0L
    private val reads = ArrayDeque<DispatchReadQuery>()
    private val resultReads = ArrayDeque<ResultReadQuery>()

    @Volatile
    var started: Boolean = false
        private set

    @Volatile
    var tradingReady: Boolean = false
        private set

    @Volatile
    var failure: Throwable? = null
        private set

    @Volatile
    var lifecycle: TradingLifecycle = TradingLifecycle.UNINITIALIZED
        private set

    @Volatile
    var appliedLogPosition: Long = 0
        private set

    @Volatile
    var malformedMessages: Long = 0
        private set

    @Volatile
    private var generation: Long? = null

    init {
        require(role == TradingServiceRole.FUNDS || role == TradingServiceRole.MATCHING) { "split service requires a split role" }
    }

    override fun onStart(
        cluster: Cluster,
        snapshotImage: Image?,
    ) {
        check(!this::cluster.isInitialized) { "종료한 분리 서비스를 다시 시작할 수 없습니다" }
        this.cluster = cluster
        serviceThread = Thread.currentThread()
        try {
            cluster.timeUnit()?.let(::requireMillisecondClock)
            appliedLogPosition = cluster.logPosition()
            if (snapshotImage != null) {
                snapshotInProgress = true
                try {
                    val input = AeronSnapshotInput(snapshotImage, cluster)
                    when (role) {
                        TradingServiceRole.FUNDS -> {
                            val state =
                                FundsRoleSnapshotCodec().read(
                                    input,
                                    configuration.dispatchConfiguration,
                                    appliedLogPosition,
                                    configuration.resultLimits,
                                )
                            checkNotNull(funds).restoreFundsRecoveryState(state, configuration.settlementCalculator())
                        }

                        TradingServiceRole.MATCHING -> {
                            val state = MatchingRoleSnapshotCodec().read(input, configuration.matchingConfiguration, appliedLogPosition)
                            checkNotNull(matching).restore(state)
                        }

                        TradingServiceRole.INTEGRATED -> {
                            error("split role required")
                        }
                    }
                } finally {
                    snapshotInProgress = false
                }
            }
            started = true
            refreshReadiness()
            // onStart 뒤에는 snapshot tail 재생이 남을 수 있다. 실제 LEADER 전환 전에는 세대를 게시하지 않는다.
        } catch (error: Throwable) {
            fail(error)
        }
    }

    override fun onSessionMessage(
        session: ClientSession?,
        timestamp: Long,
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        header: Header,
    ) {
        ensureRunning()
        try {
            requireMillisecondClock(cluster.timeUnit())
            appliedLogPosition = cluster.logPosition()
            val bytes =
                try {
                    require(
                        offset >= 0 && length in 8..SplitResultDeliveryCodec.MAX_MESSAGE_BYTES && offset <= buffer.capacity() - length,
                    ) {
                        "invalid split frame range"
                    }
                    // 두 헤더의 schema 위치가 다르다. 기존 schema88의 길이 필드를 새 schema로 오인하지 않는다.
                    val orderFrame = (buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN).toInt() and 65535) == 88
                    val resultFrame = (buffer.getShort(offset, ByteOrder.LITTLE_ENDIAN).toInt() and 65535) == 89
                    require(orderFrame || resultFrame) { "unknown split schema" }
                    require(
                        if (orderFrame) {
                            length <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES
                        } else {
                            length >=
                                SplitResultDeliveryCodec.HEADER_BYTES
                        },
                    )
                    ByteArray(length).also { buffer.getBytes(offset, it) }
                } catch (_: IllegalArgumentException) {
                    malformedMessages++
                    return
                }
            if ((buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN).toInt() and 65535) != 88) {
                onResultMessage(session, timestamp, bytes)
                refreshReadiness()
                return
            }
            val envelope =
                try {
                    codec.decode(bytes)
                } catch (_: IllegalArgumentException) {
                    malformedMessages++
                    return
                }
            val command = envelope.command
            val reply =
                when (command) {
                    SplitOrderDeliveryCommand.DescribePeer -> {
                        response(envelope.correlationId, "DESCRIBED")
                    }

                    is SplitOrderDeliveryCommand.InitializeRole -> {
                        if (command.topologySha256 != configuration.topologySha256) {
                            response(envelope.correlationId, "TOPOLOGY_CONFLICT")
                        } else {
                            val initialized =
                                when (role) {
                                    TradingServiceRole.FUNDS -> checkNotNull(funds).initialize(configuration.topology.fundsManifest)
                                    TradingServiceRole.MATCHING -> checkNotNull(matching).initialize()
                                    TradingServiceRole.INTEGRATED -> error("split role required")
                                }
                            refreshReadiness()
                            refreshGeneration()
                            response(envelope.correlationId, initialized.name)
                        }
                    }

                    is SplitOrderDeliveryCommand.Control -> {
                        if (role != TradingServiceRole.FUNDS) {
                            response(envelope.correlationId, "WRONG_ROLE")
                        } else {
                            val control = control(envelope.correlationId, command.command, timestamp)
                            response(envelope.correlationId, control.code).copy(control = control)
                        }
                    }

                    is SplitOrderDeliveryCommand.Cancel -> {
                        when {
                            role != TradingServiceRole.FUNDS -> {
                                response(envelope.correlationId, "WRONG_ROLE")
                            }

                            !resultDeliveryEnabled -> {
                                response(envelope.correlationId, "UNSUPPORTED_MODE")
                            }

                            !tradingReady -> {
                                response(envelope.correlationId, "NOT_READY")
                            }

                            else -> {
                                val cancelled = checkNotNull(funds).cancel(command.token, command.intent)
                                val code =
                                    when (cancelled) {
                                        is com.exchange.core.trading.CancelReply.Recorded -> cancelled.receipt.code.name
                                        is com.exchange.core.trading.CancelReply.Refused -> cancelled.reason
                                    }
                                response(envelope.correlationId, code).copy(cancel = cancelled)
                            }
                        }
                    }

                    is SplitOrderDeliveryCommand.Apply -> {
                        when {
                            role != TradingServiceRole.MATCHING -> {
                                response(envelope.correlationId, "WRONG_ROLE")
                            }

                            !tradingReady -> {
                                response(envelope.correlationId, "NOT_READY")
                            }

                            else -> {
                                val applied = checkNotNull(matching).apply(command.input)
                                // 전체 결과는 매칭 소유자에 남긴다. 원격으로는 적용 prefix와 GAP 위치만 보낸다.
                                response(
                                    envelope.correlationId,
                                    applied.status.name,
                                ).copy(ack = applied.ack, expectedSequence = applied.expectedSequence)
                            }
                        }
                    }

                    is SplitOrderDeliveryCommand.RecordAck -> {
                        when {
                            role != TradingServiceRole.FUNDS -> {
                                response(envelope.correlationId, "WRONG_ROLE")
                            }

                            !tradingReady -> {
                                response(envelope.correlationId, "NOT_READY")
                            }

                            else -> {
                                val recorded = checkNotNull(funds).acknowledgeMatchingApplied(command.ack)
                                val ack =
                                    if (recorded.status.name == "ACKNOWLEDGED") {
                                        FundsMatchingAppliedAck(command.ack.stream, recorded.matchingAppliedThrough, command.ack.context)
                                    } else {
                                        null
                                    }
                                response(envelope.correlationId, recorded.status.name).copy(ack = ack)
                            }
                        }
                    }
                }
            refreshReadiness()
            send(session, reply)
        } catch (error: Throwable) {
            fail(error)
        }
    }

    /** 결과 적용과 회수도 상대 호출의 수신이 아니라 자기 확정 로그 callback에서만 수행한다. */
    private fun onResultMessage(
        session: ClientSession?,
        timestamp: Long,
        bytes: ByteArray,
    ) {
        val envelope =
            try {
                resultCodec.decode(bytes)
            } catch (_: IllegalArgumentException) {
                malformedMessages++
                return
            }
        val command = envelope.command
        val correlation = envelope.correlationId
        val reply =
            when {
                !resultDeliveryEnabled -> {
                    resultResponse(correlation, "UNSUPPORTED_MODE")
                }

                command == SplitResultDeliveryCommand.DescribePeer -> {
                    resultResponse(correlation, "DESCRIBED")
                }

                !tradingReady -> {
                    resultResponse(correlation, "NOT_READY")
                }

                else -> {
                    when (command) {
                        is SplitResultDeliveryCommand.Apply -> {
                            if (role != TradingServiceRole.FUNDS) {
                                resultResponse(correlation, "WRONG_ROLE")
                            } else {
                                val applied =
                                    checkNotNull(funds).applyMatchingResult(
                                        command.result,
                                        Instant.ofEpochMilli(timestamp),
                                        settlementCalculator,
                                    )
                                resultResponse(
                                    correlation,
                                    applied.status.name,
                                ).copy(ack = applied.ack, expectedSequence = applied.expectedSequence)
                            }
                        }

                        is SplitResultDeliveryCommand.Progress -> {
                            if (role != TradingServiceRole.FUNDS) {
                                resultResponse(correlation, "WRONG_ROLE")
                            } else {
                                val ack = checkNotNull(funds).matchingSettlementAck(command.stream)
                                if (ack == null || ack.context != command.context) {
                                    resultResponse(correlation, "INVALID_STREAM")
                                } else {
                                    resultResponse(correlation, "PROGRESS").copy(ack = ack)
                                }
                            }
                        }

                        is SplitResultDeliveryCommand.RecordAck -> {
                            if (role != TradingServiceRole.MATCHING) {
                                resultResponse(correlation, "WRONG_ROLE")
                            } else {
                                val owner = checkNotNull(matching)
                                val recorded = owner.acknowledgeFundsSettled(command.ack)
                                if (recorded.status == MatchingResultAckStatus.ACKNOWLEDGED) {
                                    // 확인된 prefix의 조회는 원본을 복사하지 않고 진행 정보만 읽는다.
                                    val progress = owner.readResults(command.ack.stream, 1, 1, 1)
                                    resultResponse(correlation, recorded.status.name).copy(
                                        ack =
                                            MatchingFundsSettledAck(
                                                command.ack.stream,
                                                recorded.fundsSettledThrough,
                                                command.ack.context,
                                            ),
                                        resultIssued = progress.resultIssued,
                                    )
                                } else {
                                    resultResponse(correlation, recorded.status.name)
                                }
                            }
                        }

                        SplitResultDeliveryCommand.DescribePeer -> {
                            error("describe was handled before readiness")
                        }
                    }
                }
            }
        sendResult(session, reply)
    }

    private fun resultResponse(
        correlationId: Long,
        code: String,
    ) = SplitResultDeliveryReply(
        correlationId,
        code,
        role,
        if (role == TradingServiceRole.FUNDS) configuration.fundsLineage else configuration.matchingLineage,
        configuration.resultTopologySha256,
        appliedLogPosition,
        lifecycle = lifecycle,
    )

    private fun sendResult(
        session: ClientSession?,
        reply: SplitResultDeliveryReply,
    ) {
        if (session == null || !liveLeader || cluster.role() != Cluster.Role.LEADER) return
        val bytes = resultCodec.encodeReply(reply)
        val buffer = UnsafeBuffer(bytes)
        repeat(3) {
            if (!liveLeader || cluster.role() != Cluster.Role.LEADER) return
            val offered = session.offer(buffer, 0, bytes.size)
            if (offered > 0 || offered == Publication.CLOSED || offered == Publication.MAX_POSITION_EXCEEDED ||
                offered == Publication.NOT_CONNECTED
            ) {
                return
            }
            cluster.idleStrategy().idle()
        }
    }

    private fun control(
        correlationId: Long,
        command: BoundedTradingCommand,
        timestamp: Long,
    ): BoundedTradingReply {
        val owner = checkNotNull(funds)

        fun reply(code: String) = BoundedTradingReply(correlationId, code, appliedLogPosition, lineage = configuration.fundsLineage)
        if (command is BoundedTradingCommand.Initialize || command is BoundedTradingCommand.Execution ||
            command is BoundedTradingCommand.Quarantine
        ) {
            return reply("UNSUPPORTED_COMMAND")
        }
        if (command != BoundedTradingCommand.Progress && !tradingReady) return reply("NOT_READY")
        return when (command) {
            is BoundedTradingCommand.Funding -> {
                try {
                    reply("PREPARED").copy(funding = owner.prepareDevelopmentBalance(command.command.opening, command.command.occurredAt))
                } catch (_: OpeningBalanceRequestConflictException) {
                    reply("PREPARATION_CONTENT_CONFLICT")
                } catch (_: OpeningBalanceConflictException) {
                    reply("ACCOUNT_ALREADY_USED")
                } catch (error: TradingFundingInputException) {
                    reply(error.reason.name)
                } catch (_: TradingCapacityException) {
                    reply("RETRYABLE_CAPACITY")
                }
            }

            is BoundedTradingCommand.Rotate -> {
                owner.rotateEpoch(command.userId, command.channel, command.expectedEpoch).let {
                    reply(it.status.name).copy(epoch = it.epoch)
                }
            }

            is BoundedTradingCommand.Acknowledge -> {
                owner.acknowledgeReceipts(command.userId, command.channel, command.epoch, command.through).let {
                    reply(it.status.name).copy(epoch = it.epoch, retiredThrough = it.retiredThrough)
                }
            }

            is BoundedTradingCommand.Admit -> {
                when (val result = owner.admit(command.token, command.intent, Instant.ofEpochMilli(timestamp))) {
                    is AdmissionReply.Refused -> reply(result.reason)
                    is AdmissionReply.Recorded -> reply(result.receipt.code.name).copy(admission = result.receipt)
                }
            }

            BoundedTradingCommand.Progress -> {
                owner.progress().let {
                    reply("PROGRESS").copy(
                        lifecycle = it.lifecycle.name,
                        lastAdmissionSequence = it.lastAdmissionSequence,
                        markets =
                            it.markets.map { market ->
                                BoundedMarketProgress(market.marketId.value, market.lastAssigned, market.executedThrough)
                            },
                    )
                }
            }

            else -> {
                reply("UNSUPPORTED_COMMAND")
            }
        }
    }

    private fun response(
        correlationId: Long,
        code: String,
    ) = SplitOrderDeliveryReply(
        correlationId,
        code,
        role,
        if (role == TradingServiceRole.FUNDS) configuration.fundsLineage else configuration.matchingLineage,
        configuration.topologySha256,
        appliedLogPosition,
        lifecycle = lifecycle.name,
    )

    private fun send(
        session: ClientSession?,
        reply: SplitOrderDeliveryReply,
    ) {
        // replay·팔로워의 mocked offer 양수를 송신 증거로 삼지 않는다.
        if (session == null || !liveLeader || cluster.role() != Cluster.Role.LEADER) return
        val bytes = codec.encodeReply(reply)
        val buffer = UnsafeBuffer(bytes)
        repeat(3) {
            if (!liveLeader || cluster.role() != Cluster.Role.LEADER) return
            val offered = session.offer(buffer, 0, bytes.size)
            if (offered > 0 || offered == Publication.CLOSED || offered == Publication.MAX_POSITION_EXCEEDED ||
                offered == Publication.NOT_CONNECTED
            ) {
                return
            }
            cluster.idleStrategy().idle()
        }
    }

    override fun activeGeneration(): Long? = if (role == TradingServiceRole.FUNDS) generation else null

    override fun activeResultGeneration(): Long? = if (role == TradingServiceRole.MATCHING && resultDeliveryEnabled) generation else null

    override fun readDispatch(
        generation: Long,
        stream: FundsDispatchStream,
        fromSequence: Long,
        maxEntries: Int,
        maxBytes: Long,
    ): CompletableFuture<FundsDispatchRead> {
        val result = CompletableFuture<FundsDispatchRead>()
        synchronized(reads) {
            when {
                this.generation != generation || role != TradingServiceRole.FUNDS -> {
                    result.completeExceptionally(IllegalStateException("자금 리더 세대가 활성 상태가 아닙니다"))
                }

                maxEntries > configuration.deliveryLimits.maxReadEntries || maxBytes > configuration.deliveryLimits.maxReadBytes -> {
                    result.completeExceptionally(IllegalArgumentException("전달 조회가 고정된 batch 한도를 넘었습니다"))
                }

                reads.size >= MAX_READ_REQUESTS -> {
                    result.completeExceptionally(IllegalStateException("전달 조회 요청 버퍼가 가득 찼습니다"))
                }

                else -> {
                    reads.addLast(DispatchReadQuery(generation, stream, fromSequence, maxEntries, maxBytes, result))
                }
            }
        }
        return result
    }

    override fun readResults(
        generation: Long,
        stream: MatchingResultStream,
        fromSequence: Long,
        maxEntries: Int,
        maxBytes: Long,
    ): CompletableFuture<MatchingResultRead> {
        val result = CompletableFuture<MatchingResultRead>()
        synchronized(reads) {
            when {
                this.generation != generation || role != TradingServiceRole.MATCHING || !resultDeliveryEnabled -> {
                    result.completeExceptionally(IllegalStateException("매칭 리더 세대가 활성 상태가 아닙니다"))
                }

                maxEntries > configuration.resultDeliveryLimits.maxReadEntries ||
                    maxBytes > configuration.resultDeliveryLimits.maxReadBytes -> {
                    result.completeExceptionally(IllegalArgumentException("결과 조회가 고정 batch 한도를 넘었습니다"))
                }

                resultReads.size >= MAX_RESULT_READ_REQUESTS -> {
                    result.completeExceptionally(IllegalStateException("결과 조회 요청 버퍼가 가득 찼습니다"))
                }

                else -> {
                    resultReads.addLast(ResultReadQuery(generation, stream, fromSequence, maxEntries, maxBytes, result))
                }
            }
        }
        return result
    }

    override fun doBackgroundWork(nowNs: Long): Int {
        if (!started || snapshotInProgress || generation == null) return 0
        ensureRunning()
        if (role == TradingServiceRole.MATCHING) return readMatchingResults()
        var work = 0
        repeat(MAX_READ_REQUESTS) {
            val request = synchronized(reads) { if (reads.isEmpty()) null else reads.removeFirst() } ?: return work
            work++
            if (request.result.isDone) return@repeat
            if (request.generation != generation) {
                request.result.completeExceptionally(IllegalStateException("전달 조회의 리더 세대가 만료됐습니다"))
                return@repeat
            }
            try {
                request.result.complete(
                    checkNotNull(funds).pendingDispatch(request.stream, request.fromSequence, request.maxEntries, request.maxBytes),
                )
            } catch (error: Throwable) {
                request.result.completeExceptionally(error)
            }
        }
        return work
    }

    private fun readMatchingResults(): Int {
        var work = 0
        repeat(MAX_RESULT_READ_REQUESTS) {
            val request = synchronized(reads) { if (resultReads.isEmpty()) null else resultReads.removeFirst() } ?: return work
            work++
            if (request.result.isDone) return@repeat
            if (request.generation != generation) {
                request.result.completeExceptionally(IllegalStateException("결과 조회의 리더 세대가 만료됐습니다"))
                return@repeat
            }
            try {
                request.result.complete(
                    checkNotNull(matching).readResults(request.stream, request.fromSequence, request.maxEntries, request.maxBytes),
                )
            } catch (error: Throwable) {
                request.result.completeExceptionally(error)
            }
        }
        return work
    }

    override fun onTakeSnapshot(snapshotPublication: ExclusivePublication) {
        ensureRunning()
        try {
            check(tradingReady && lifecycle == TradingLifecycle.RUNNING) { "초기화 전 분리 역할은 Snapshot을 만들 수 없습니다" }
            snapshotInProgress = true
            invalidateGeneration("Snapshot 처리로 전달 조회 세대가 만료됐습니다")
            val output = AeronSnapshotOutput(snapshotPublication, cluster)
            when (role) {
                TradingServiceRole.FUNDS -> {
                    FundsRoleSnapshotCodec().write(
                        checkNotNull(funds).fundsRecoveryState(),
                        cluster.logPosition(),
                        output,
                    )
                }

                TradingServiceRole.MATCHING -> {
                    MatchingRoleSnapshotCodec().write(
                        checkNotNull(matching).recoveryState(),
                        cluster.logPosition(),
                        output,
                    )
                }

                TradingServiceRole.INTEGRATED -> {
                    error("split role required")
                }
            }
            output.flush()
        } catch (error: Throwable) {
            fail(error)
        } finally {
            snapshotInProgress = false
            refreshGeneration()
        }
    }

    override fun onRoleChange(newRole: Cluster.Role) {
        liveLeader = newRole == Cluster.Role.LEADER
        invalidateGeneration("역할 변경으로 전달 조회 세대가 만료됐습니다")
        refreshGeneration()
    }

    private fun refreshReadiness() {
        lifecycle = funds?.lifecycle ?: checkNotNull(matching).lifecycle
        tradingReady = started && failure == null && lifecycle == TradingLifecycle.RUNNING
    }

    private fun refreshGeneration() {
        val sends = role == TradingServiceRole.FUNDS || (resultDeliveryEnabled && role == TradingServiceRole.MATCHING)
        if (generation == null && sends && started && tradingReady && liveLeader && !snapshotInProgress &&
            failure == null
        ) {
            synchronized(reads) {
                generationCounter = Math.addExact(generationCounter, 1L)
                generation = generationCounter
            }
        }
    }

    private fun invalidateGeneration(reason: String) {
        val waiting =
            synchronized(reads) {
                generation = null
                (reads.map { it.result } + resultReads.map { it.result }).also {
                    reads.clear()
                    resultReads.clear()
                }
            }
        waiting.forEach { it.completeExceptionally(IllegalStateException(reason)) }
    }

    private fun ensureRunning() {
        failure?.let { throw AgentTerminationException("분리 역할 서비스가 중단됐습니다", it) }
        check(started && Thread.currentThread() === serviceThread) { "분리 상태는 실행 중인 서비스 스레드에서만 접근합니다" }
    }

    private fun requireMillisecondClock(unit: TimeUnit?) {
        check(unit == TimeUnit.MILLISECONDS) { "개발 거래 시각은 밀리초여야 합니다" }
    }

    private fun fail(error: Throwable): Nothing {
        failure = error
        started = false
        tradingReady = false
        liveLeader = false
        invalidateGeneration("분리 역할 서비스가 실패했습니다")
        try {
            onFatal(error)
        } catch (notificationFailure: Throwable) {
            if (notificationFailure !== error) error.addSuppressed(notificationFailure)
        }
        throw AgentTerminationException("분리 역할 서비스가 실패했습니다", error)
    }

    override fun onTerminate(cluster: Cluster) {
        started = false
        tradingReady = false
        liveLeader = false
        invalidateGeneration("분리 역할 서비스가 종료됐습니다")
    }

    override fun onSessionOpen(
        session: ClientSession,
        timestamp: Long,
    ) = Unit

    override fun onSessionClose(
        session: ClientSession,
        timestamp: Long,
        closeReason: CloseReason,
    ) = Unit

    override fun onTimerEvent(
        correlationId: Long,
        timestamp: Long,
    ) = Unit

    override fun onNewLeadershipTermEvent(
        leadershipTermId: Long,
        logPosition: Long,
        timestamp: Long,
        termBaseLogPosition: Long,
        leaderMemberId: Int,
        logSessionId: Int,
        timeUnit: TimeUnit,
        appVersion: Int,
    ) {
        try {
            requireMillisecondClock(timeUnit)
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private data class DispatchReadQuery(
        val generation: Long,
        val stream: FundsDispatchStream,
        val fromSequence: Long,
        val maxEntries: Int,
        val maxBytes: Long,
        val result: CompletableFuture<FundsDispatchRead>,
    )

    private data class ResultReadQuery(
        val generation: Long,
        val stream: MatchingResultStream,
        val fromSequence: Long,
        val maxEntries: Int,
        val maxBytes: Long,
        val result: CompletableFuture<MatchingResultRead>,
    )

    companion object {
        private const val MAX_RESULT_READ_REQUESTS = 4
        private const val MAX_READ_REQUESTS = 16
    }
}
