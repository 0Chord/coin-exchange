package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageException
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageFormat
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageReader
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionCodec
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionPayload
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.CloseReason
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.NanoClock
import org.agrona.concurrent.SystemNanoClock
import org.agrona.concurrent.UnsafeBuffer
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 서비스 스레드가 완성한 값만 외부에 공개한다. 영구 Aeron Snapshot과는 다르다. */
internal data class FundingObservation(
    val position: Long,
    /** 명시적 상태 관찰에서만 채운다. 일반 명령의 관찰은 누적 상태를 복사하지 않는다. */
    val snapshot: TradingState.Snapshot?,
    val result: DevelopmentBalanceResultPayload?,
    val failure: String? = null,
    val malformedMessages: Long = 0,
    val replyAttempts: Int = 0,
    val replyStatus: String? = null,
    val blocked: Boolean = false,
    val admissionResult: AdmissionResultPayload? = null,
    val appliedRole: String? = null,
    val executionSequence: Long? = null,
    val executionCompletion: TradingState.Completion? = null,
)

/** 확정 callback에서 같은 코어에 동기 적용하고, 이미 만든 결과의 전달만 재시도한다. */
internal class DevelopmentFundingAdapter(
    private val state: TradingState,
    private val maxReplyAttempts: Int = 100,
    private val replyTimeoutNs: Long = 100_000_000,
    private val markets: DevelopmentMarketRegistry = DevelopmentMarketRegistry(listOf(DevelopmentMarketContext.BTC_USDT)),
    private val serviceId: Int = 0,
    private val maxExecutionOfferAttempts: Int = 1_000,
    private val executionOfferTimeoutNs: Long = 1_000_000_000,
    private var telemetry: TradingNodeTelemetry = TradingNodeTelemetry.NONE,
    private var telemetryNanoTime: NanoClock = SystemNanoClock.INSTANCE,
    private val snapshots: TradingStateSnapshotPersistence? = null,
    private val onFatal: (Throwable) -> Unit,
) : ClusteredService {
    private val commands = DevelopmentBalanceCommandCodec()
    private val results = DevelopmentBalanceResultCodec()
    private val admissionCommands = AdmissionCommandCodec()
    private val admissionResults = AdmissionResultCodec()
    private val executionCommands = ExecuteAdmissionCodec()
    private val executionClaim = BufferClaim()
    private lateinit var cluster: Cluster
    private var malformedMessages = 0L
    private var snapshotInProgress = false
    private var fatalCause: Throwable? = null
    private lateinit var serviceThread: Thread
    private val observationCaptures = ArrayDeque<CompletableFuture<FundingObservation?>>()
    private var callbackCapture: TradingNodeCallbackCapture? = null
    private var reusableCallbackCapture: TradingNodeCallbackCapture? = null
    private var telemetryRecordingFailures = 0L
    val telemetryOperational: Boolean get() = telemetryRecordingFailures == 0L && telemetry.isOperational
    val telemetryFailureCount: Long get() = telemetryRecordingFailures + telemetry.failureCount

    /** 노드 시작 전 composition만 허용한다. 서비스 스레드의 callback 관측자를 실행 중 교체하지 않는다. */
    fun attachTelemetry(
        observer: TradingNodeTelemetry,
        nanoTime: NanoClock = telemetryNanoTime,
    ) {
        check(!this::cluster.isInitialized) { "노드 계측은 onStart 전에만 연결할 수 있습니다" }
        telemetry = if (telemetry === TradingNodeTelemetry.NONE) observer else TradingNodeTelemetry.compose(telemetry, observer)
        telemetryNanoTime = nanoTime
    }

    @Volatile
    var observation: FundingObservation? = null
        private set

    @Volatile
    var started: Boolean = false
        private set

    init {
        require(maxReplyAttempts > 0) { "응답 재시도 횟수는 양수여야 합니다" }
        require(replyTimeoutNs > 0) { "응답 재시도 기한은 양수여야 합니다" }
        require(serviceId in 0..127) { "서비스 ID 범위를 벗어났습니다" }
        require(maxExecutionOfferAttempts > 0 && executionOfferTimeoutNs > 0) { "실행 제출의 유한 재시도 한도가 필요합니다" }
    }

    override fun onStart(
        cluster: Cluster,
        snapshotImage: Image?,
    ) {
        if (this::cluster.isInitialized) fail(IllegalStateException("종료한 개발 서비스를 다시 시작할 수 없습니다"))
        this.cluster = cluster
        serviceThread = Thread.currentThread()
        if (snapshotImage != null) {
            snapshotInProgress = true
            try {
                val persistence = snapshots ?: throw UnsupportedOperationException("Snapshot 저장소가 연결되지 않았습니다")
                malformedMessages = persistence.restore(state, snapshotImage, cluster)
                observation =
                    FundingObservation(
                        cluster.logPosition(),
                        null,
                        null,
                        malformedMessages = malformedMessages,
                        replyStatus = "RESTORED_SNAPSHOT",
                    )
            } catch (failure: Throwable) {
                fail(failure)
            } finally {
                snapshotInProgress = false
            }
        }
        // 새 클러스터의 onStart에는 아직 leadership term이 없어 timeUnit이 null이다.
        cluster.timeUnit()?.let { requireMillisecondClock(it) }
        started = true
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
        requireMillisecondClock(cluster.timeUnit())
        val outerCapture = callbackCapture
        val capture =
            if (telemetry === TradingNodeTelemetry.NONE) {
                null
            } else {
                val reusable = reusableCallbackCapture
                reusableCallbackCapture = null
                if (reusable == null) TradingNodeCallbackCapture(telemetryNanoTime) else reusable.also { it.reset() }
            }
        callbackCapture = capture
        var callbackFailure: Throwable? = null
        try {
            val internal = ClusterMessageOrigin.isServiceMessage(buffer, offset, length, serviceId)
            val command =
                try {
                    measure(TradingNodePhase.DECODE) { decode(buffer, offset, length, internal) }
                } catch (error: AdmissionMessageException) {
                    if (internal) throw error
                    capture?.kind = TradingNodeCallbackKind.MALFORMED
                    malformedMessages++
                    observation =
                        FundingObservation(
                            cluster.logPosition(),
                            snapshot = null,
                            result = null,
                            malformedMessages = malformedMessages,
                            replyStatus = "MALFORMED_OR_UNSUPPORTED: ${error.message}",
                        )
                    return
                }

            when (command) {
                is Command.Funding -> {
                    capture?.apply {
                        kind = TradingNodeCallbackKind.FUNDING
                        userId = command.value.opening.userId.value
                        requestId = command.value.opening.preparationId
                    }
                }

                is Command.Admission -> {
                    capture?.apply {
                        kind = TradingNodeCallbackKind.ADMISSION
                        userId = command.value.order.userId.value
                        requestId = command.value.requestId
                    }
                }

                is Command.Execution -> {
                    capture?.apply {
                        kind = TradingNodeCallbackKind.EXECUTION
                        sequence = command.value.admissionSequence
                    }
                }
            }

            // 역할·세션·전송 성공 여부가 확정된 업무 적용을 바꾸지 않는다.
            var fundingResult: DevelopmentBalanceResultPayload? = null
            var admissionResult: AdmissionResultPayload? = null
            var executionSequence: Long? = null
            var executionCompletion: TradingState.Completion? = null
            val reply =
                when (command) {
                    is Command.Funding -> {
                        val prepared = measure(TradingNodePhase.FUNDING_APPLY) { prepare(command.value) }
                        fundingResult = prepared
                        if (cluster.role() == Cluster.Role.LEADER) {
                            measure(TradingNodePhase.REPLY_ENCODE) { results.encode(prepared) }
                        } else {
                            null
                        }
                    }

                    is Command.Admission -> {
                        val admitted = admit(command.value)
                        admissionResult = admitted
                        if (cluster.role() == Cluster.Role.LEADER) {
                            measure(TradingNodePhase.REPLY_ENCODE) { admissionResults.encode(admitted) }
                        } else {
                            null
                        }
                    }

                    is Command.Execution -> {
                        val sequence = command.value.admissionSequence
                        executionSequence = sequence
                        executionCompletion =
                            measure(TradingNodePhase.EXECUTION_TOTAL) {
                                if (capture == null) {
                                    state.executePending(sequence, Instant.ofEpochMilli(timestamp))
                                } else {
                                    state.executePending(sequence, Instant.ofEpochMilli(timestamp), capture)
                                }
                            }
                        null
                    }
                }
            val applied =
                FundingObservation(
                    cluster.logPosition(),
                    snapshot = null,
                    result = fundingResult,
                    malformedMessages = malformedMessages,
                    replyStatus =
                        when {
                            command is Command.Execution -> "EXECUTED"
                            reply != null -> "PENDING"
                            session == null -> "NO_SESSION"
                            else -> "NOT_LEADER"
                        },
                    admissionResult = admissionResult,
                    appliedRole = cluster.role().name,
                    executionSequence = executionSequence,
                    executionCompletion = executionCompletion,
                )
            observation = applied
            if (reply != null) observation = measure(TradingNodePhase.REPLY_OFFER) { sendReply(session, reply, applied) }
        } catch (error: Throwable) {
            callbackFailure = error
            capture?.failed = true
            fail(error)
        } finally {
            callbackCapture = outerCapture
            if (capture != null) {
                try {
                    capture.complete()
                    val applied = observation
                    telemetry.recordCompleted(capture, applied?.position ?: 0, applied?.appliedRole ?: "UNKNOWN", applied?.replyStatus)
                } catch (_: Exception) {
                    telemetryRecordingFailures++
                    // 시간·운영 metric·테스트 observer의 실패는 원래 callback의 결과나 예외를 덮지 않는다.
                } catch (observerFailure: Error) {
                    val original = callbackFailure
                    if (original == null) throw observerFailure
                    if (observerFailure !== original) original.addSuppressed(observerFailure)
                } finally {
                    // 관측자가 돌아온 뒤에만 재사용한다. 중첩 callback은 별도의 capture를 사용한다.
                    reusableCallbackCapture = capture
                }
            }
        }
    }

    private inline fun <T> measure(
        phase: TradingNodePhase,
        action: () -> T,
    ): T {
        val capture = callbackCapture
        capture?.begin(phase)
        return try {
            action()
        } finally {
            capture?.end(phase)
        }
    }

    /** 외부 조회는 서비스 스레드의 다음 유휴 작업에서 불변 복사본을 받는다. 일반 명령은 이 요청을 만들지 않는다. */
    fun captureObservation(): CompletableFuture<FundingObservation?> {
        val capture = CompletableFuture<FundingObservation?>()
        synchronized(observationCaptures) {
            if (!started) {
                capture.completeExceptionally(IllegalStateException("개발 자금 서비스가 준비되지 않았습니다"))
            } else if (Thread.currentThread() === serviceThread && !snapshotInProgress) {
                completeCapture(capture)
            } else if (observationCaptures.size >= 16) {
                capture.completeExceptionally(IllegalStateException("상태 관찰 요청 버퍼가 가득 찼습니다"))
            } else {
                observationCaptures.addLast(capture)
            }
        }
        return capture
    }

    /** callback과 doBackgroundWork 안에서만 읽는다. 반환값은 이후 업무 쓰기로 바뀌지 않는다. */
    fun captureSnapshotOnServiceThread(): TradingState.Snapshot {
        check(this::serviceThread.isInitialized && Thread.currentThread() === serviceThread) { "상태 복사는 서비스 스레드에서만 가능합니다" }
        check(started) { "개발 자금 서비스가 준비되지 않았습니다" }
        check(!snapshotInProgress) { "영구 Snapshot 처리 중에는 상태 관찰을 미룹니다" }
        return state.snapshot()
    }

    private fun completeCapture(capture: CompletableFuture<FundingObservation?>) {
        if (capture.isCancelled) return
        try {
            val current = observation
            // 잘못된 외부 메시지와 종료 관찰은 기존처럼 정상 상태를 담지 않는다.
            val captured =
                if (current == null || current.failure != null ||
                    (
                        current.result == null && current.admissionResult == null && current.executionCompletion == null &&
                            current.replyStatus != "RESTORED_SNAPSHOT"
                    )
                ) {
                    current
                } else {
                    current.copy(snapshot = captureSnapshotOnServiceThread())
                }
            capture.complete(captured)
        } catch (error: Throwable) {
            capture.completeExceptionally(error)
        }
    }

    private fun rejectCaptures(error: Throwable) {
        synchronized(observationCaptures) {
            while (observationCaptures.isNotEmpty()) observationCaptures.removeFirst().completeExceptionally(error)
        }
    }

    private fun decode(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        internal: Boolean,
    ): Command {
        if (offset < 0 || length < AdmissionMessageFormat.HEADER_LENGTH ||
            length > AdmissionMessageFormat.MAX_MESSAGE_LENGTH || offset > buffer.capacity() - length
        ) {
            throw AdmissionMessageException("메시지 구간 또는 전체 길이가 잘못됐습니다")
        }
        val bytes = ByteArray(length)
        buffer.getBytes(offset, bytes)
        val kind = AdmissionMessageReader(bytes, 0, bytes.size).kind
        if (internal) {
            if (kind != AdmissionMessageFormat.EXECUTE_ADMISSION) throw AdmissionMessageException("내부 실행 명령 종류가 잘못됐습니다")
            return Command.Execution(executionCommands.decode(bytes))
        }
        return when (kind) {
            AdmissionMessageFormat.DEVELOPMENT_BALANCE_COMMAND -> Command.Funding(commands.decode(bytes))
            AdmissionMessageFormat.COMMAND -> Command.Admission(admissionCommands.decode(bytes))
            else -> throw AdmissionMessageException("지원하지 않는 명령 종류입니다")
        }
    }

    private sealed interface Command {
        data class Funding(
            val value: DevelopmentBalanceCommandPayload,
        ) : Command

        data class Admission(
            val value: AdmissionCommandPayload,
        ) : Command

        data class Execution(
            val value: ExecuteAdmissionPayload,
        ) : Command
    }

    private fun admit(command: AdmissionCommandPayload): AdmissionResultPayload {
        // 기존 결과가 있으면 새 실행 문맥보다 코어의 최초 판단이 우선한다.
        var knownRequest = false
        val context =
            measure(TradingNodePhase.ADMISSION_CONTEXT) {
                knownRequest = state.hasRequest(command.order.userId, command.requestId)
                val selected = if (knownRequest) null else markets[command.order.marketId]
                if (!knownRequest) {
                    if (selected == null || command.market != selected.market || command.feePolicySnapshot != selected.feePolicySnapshot) {
                        return AdmissionResultPayload.Rejected(
                            command.order.userId.value,
                            command.requestId,
                            command.order.marketId.value,
                            command.order.orderId.value,
                            AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH,
                        )
                    }
                }
                selected
            }
        val result =
            measure(TradingNodePhase.ADMISSION_APPLY) {
                state.admit(
                    command.requestId,
                    command.order,
                    context?.market ?: command.market,
                    context?.feePolicySnapshot ?: command.feePolicySnapshot,
                    command.ledgerTransactionId,
                    command.occurredAt,
                )
            }
        callbackCapture?.sequence = (result as? OrderAdmissionState.Result.Accepted)?.record?.sequence
        if (!knownRequest && result is OrderAdmissionState.Result.Accepted) submitExecution(result.record.sequence)
        return AdmissionResultPayload.from(command, result)
    }

    /** 모든 사본이 같은 확정 승인에서 같은 순서로 생성한다. 업무 실행은 이 입력의 확정 뒤다. */
    private fun submitExecution(sequence: Long) {
        val length = measure(TradingNodePhase.EXECUTION_ENCODE) { executionCommands.encodedLength(sequence) }
        val startNs = System.nanoTime()
        for (attempt in 1..maxExecutionOfferAttempts) {
            check(attempt == 1 || System.nanoTime() - startNs < executionOfferTimeoutNs) { "내부 실행 제출 기한을 초과했습니다: $sequence" }
            callbackCapture?.let { it.executionOfferAttempts++ }
            var acquired = false
            var committed = false
            val position =
                try {
                    val offered =
                        measure(TradingNodePhase.EXECUTION_OFFER) {
                            cluster.tryClaim(length, executionClaim).also { acquired = it > 0 }
                        }
                    callbackCapture?.executionOffer(offered)
                    if (offered > 0) {
                        measure(TradingNodePhase.EXECUTION_ENCODE) {
                            executionCommands.encodeInto(
                                sequence,
                                executionClaim.buffer(),
                                executionClaim.offset() + AeronCluster.SESSION_HEADER_LENGTH,
                            )
                        }
                        measure(TradingNodePhase.EXECUTION_OFFER) {
                            executionClaim.commit()
                            committed = true
                        }
                    }
                    offered
                } catch (failure: Throwable) {
                    // 계측의 finally가 실패해도 이미 공개한 frame을 PAD로 바꾸지 않는다.
                    if (acquired && !committed) {
                        try {
                            executionClaim.abort()
                        } catch (abortFailure: Throwable) {
                            if (abortFailure !== failure) failure.addSuppressed(abortFailure)
                        }
                    }
                    throw failure
                }
            if (position > 0) return
            check(position == Publication.BACK_PRESSURED || position == Publication.ADMIN_ACTION) {
                "내부 실행을 제출할 수 없습니다: sequence=$sequence, result=$position"
            }
            check(attempt < maxExecutionOfferAttempts && System.nanoTime() - startNs < executionOfferTimeoutNs) {
                "내부 실행 제출 재시도 한도를 초과했습니다: $sequence"
            }
            measure(TradingNodePhase.EXECUTION_OFFER) { cluster.idleStrategy().idle() }
        }
    }

    private fun prepare(command: DevelopmentBalanceCommandPayload): DevelopmentBalanceResultPayload {
        val result =
            try {
                state.prepareDevelopmentBalance(command.opening, command.ledgerTransactionId, command.occurredAt)
            } catch (error: OpeningBalanceRequestConflictException) {
                return DevelopmentBalanceResultPayload.from(command, error)
            } catch (error: OpeningBalanceConflictException) {
                return DevelopmentBalanceResultPayload.from(command, error)
            }
        return DevelopmentBalanceResultPayload.from(command, result)
    }

    private fun sendReply(
        session: ClientSession?,
        bytes: ByteArray,
        applied: FundingObservation,
    ): FundingObservation {
        if (session == null) return applied.copy(replyStatus = "NO_SESSION")
        val buffer = UnsafeBuffer(bytes)
        val startNs = System.nanoTime()
        for (attempt in 1..maxReplyAttempts) {
            if (attempt > 1 && System.nanoTime() - startNs >= replyTimeoutNs) {
                return applied.copy(replyAttempts = attempt - 1, replyStatus = "DEADLINE_EXCEEDED")
            }
            if (cluster.role() != Cluster.Role.LEADER) {
                return applied.copy(replyAttempts = attempt - 1, replyStatus = "NOT_LEADER")
            }
            if (session.isClosing) return applied.copy(replyAttempts = attempt - 1, replyStatus = "SESSION_CLOSING")
            callbackCapture?.let { it.replyAttempts++ }
            val position = session.offer(buffer, 0, bytes.size)
            callbackCapture?.replyOffer(position)
            if (position > 0) return applied.copy(replyAttempts = attempt, replyStatus = "OFFERED")
            val terminal =
                when (position) {
                    Publication.BACK_PRESSURED, Publication.ADMIN_ACTION -> null
                    Publication.NOT_CONNECTED -> "NOT_CONNECTED"
                    Publication.CLOSED -> "CLOSED"
                    Publication.MAX_POSITION_EXCEEDED -> "MAX_POSITION_EXCEEDED"
                    else -> "UNEXPECTED_PUBLICATION_RESULT: $position"
                }
            if (terminal != null) return applied.copy(replyAttempts = attempt, replyStatus = terminal)
            if (attempt == maxReplyAttempts) {
                return applied.copy(replyAttempts = attempt, replyStatus = "ATTEMPTS_EXHAUSTED")
            }
            if (System.nanoTime() - startNs >= replyTimeoutNs) {
                return applied.copy(replyAttempts = attempt, replyStatus = "DEADLINE_EXCEEDED")
            }
            cluster.idleStrategy().idle()
        }
        error("응답 재시도 경계에 도달할 수 없습니다")
    }

    private fun ensureRunning() {
        fatalCause?.let { throw AgentTerminationException("개발 자금 서비스가 이미 실패했습니다", it) }
        if (!started) fail(IllegalStateException("개발 자금 서비스가 준비되지 않았습니다"))
    }

    private fun fail(error: Throwable): Nothing {
        fatalCause = error
        started = false
        rejectCaptures(error)
        observation =
            FundingObservation(
                position = if (this::cluster.isInitialized) cluster.logPosition() else 0,
                snapshot = null,
                result = null,
                failure = "${error.javaClass.simpleName}: ${error.message}",
                malformedMessages = malformedMessages,
                blocked = state.isBlocked,
            )
        try {
            onFatal(error)
        } catch (notificationError: Throwable) {
            if (notificationError !== error) error.addSuppressed(notificationError)
        }
        throw AgentTerminationException("개발 자금 서비스를 중단합니다", error)
    }

    override fun onTakeSnapshot(snapshotPublication: ExclusivePublication) {
        ensureRunning()
        snapshotInProgress = true
        try {
            val persistence = snapshots ?: throw UnsupportedOperationException("Snapshot 저장소가 연결되지 않았습니다")
            persistence.save(state, snapshotPublication, cluster, malformedMessages)
        } catch (failure: Throwable) {
            fail(failure)
        } finally {
            snapshotInProgress = false
        }
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
        requireMillisecondClock(timeUnit)
    }

    private fun requireMillisecondClock(timeUnit: TimeUnit?) {
        if (timeUnit != TimeUnit.MILLISECONDS) fail(IllegalStateException("개발 거래 시각은 밀리초여야 합니다"))
    }

    override fun onRoleChange(newRole: Cluster.Role) = Unit

    override fun doBackgroundWork(nowNs: Long): Int {
        // Aeron idle() re-enters this callback while streaming a snapshot. Keep queued
        // reads until its exclusive history export/import has finished.
        if (snapshotInProgress || !started) return 0
        val captures =
            synchronized(observationCaptures) {
                if (observationCaptures.isEmpty()) return 0
                observationCaptures.toList().also { observationCaptures.clear() }
            }
        val waiting = captures.filterNot { it.isCancelled }
        if (waiting.isNotEmpty()) {
            // 같은 관찰 시점의 외부 요청은 전체 상태를 한 번만 복사해 나눠 받는다.
            val shared = CompletableFuture<FundingObservation?>()
            completeCapture(shared)
            shared.whenComplete { captured, error ->
                waiting.forEach { capture ->
                    if (error == null) capture.complete(captured) else capture.completeExceptionally(error)
                }
            }
        }
        return captures.size
    }

    override fun onTerminate(cluster: Cluster) {
        started = false
        rejectCaptures(IllegalStateException("개발 자금 서비스가 종료됐습니다"))
    }
}
