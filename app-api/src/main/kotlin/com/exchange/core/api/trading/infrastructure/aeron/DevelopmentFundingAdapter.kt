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
import io.aeron.cluster.codecs.CloseReason
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.UnsafeBuffer
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 서비스 스레드가 완성한 값만 외부에 공개한다. 영구 Aeron Snapshot과는 다르다. */
internal data class FundingObservation(
    val position: Long,
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
    private val onFatal: (Throwable) -> Unit,
) : ClusteredService {
    private val commands = DevelopmentBalanceCommandCodec()
    private val results = DevelopmentBalanceResultCodec()
    private val admissionCommands = AdmissionCommandCodec()
    private val admissionResults = AdmissionResultCodec()
    private val executionCommands = ExecuteAdmissionCodec()
    private lateinit var cluster: Cluster
    private var malformedMessages = 0L
    private var fatalCause: Throwable? = null

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
        if (snapshotImage != null) fail(UnsupportedOperationException("개발 자금 상태의 Snapshot 복구는 지원하지 않습니다"))
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
        try {
            val internal = ClusterMessageOrigin.isServiceMessage(buffer, offset, length, serviceId)
            val command =
                try {
                    decode(buffer, offset, length, internal)
                } catch (error: AdmissionMessageException) {
                    if (internal) throw error
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

            // 역할·세션·전송 성공 여부가 확정된 업무 적용을 바꾸지 않는다.
            var fundingResult: DevelopmentBalanceResultPayload? = null
            var admissionResult: AdmissionResultPayload? = null
            var executionSequence: Long? = null
            val reply =
                when (command) {
                    is Command.Funding -> {
                        fundingResult = prepare(command.value)
                        results.encode(fundingResult)
                    }

                    is Command.Admission -> {
                        admissionResult = admit(command.value)
                        admissionResults.encode(admissionResult)
                    }

                    is Command.Execution -> {
                        executionSequence = command.value.admissionSequence
                        state.executePending(executionSequence, Instant.ofEpochMilli(timestamp))
                        null
                    }
                }
            val applied =
                FundingObservation(
                    cluster.logPosition(),
                    state.snapshot(),
                    fundingResult,
                    malformedMessages = malformedMessages,
                    replyStatus = if (reply == null) "EXECUTED" else "PENDING",
                    admissionResult = admissionResult,
                    appliedRole = cluster.role().name,
                    executionSequence = executionSequence,
                )
            observation = applied
            if (reply != null) observation = sendReply(session, reply, applied)
        } catch (error: Throwable) {
            fail(error)
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
        val knownRequest = state.hasRequest(command.order.userId, command.requestId)
        if (!knownRequest) {
            val context = markets[command.order.marketId]
            if (context == null || command.market != context.market || command.feePolicySnapshot != context.feePolicySnapshot) {
                return AdmissionResultPayload.Rejected(
                    command.order.userId.value,
                    command.requestId,
                    command.order.marketId.value,
                    command.order.orderId.value,
                    AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH,
                )
            }
        }
        val result =
            state.admit(
                command.requestId,
                command.order,
                command.market,
                command.feePolicySnapshot,
                command.ledgerTransactionId,
                command.occurredAt,
            )
        if (!knownRequest && result is OrderAdmissionState.Result.Accepted) submitExecution(result.record.sequence)
        return AdmissionResultPayload.from(command, result)
    }

    /** 모든 사본이 같은 확정 승인에서 같은 순서로 생성한다. 업무 실행은 이 입력의 확정 뒤다. */
    private fun submitExecution(sequence: Long) {
        val bytes = executionCommands.encode(ExecuteAdmissionPayload(sequence))
        val buffer = UnsafeBuffer(bytes)
        val startNs = System.nanoTime()
        for (attempt in 1..maxExecutionOfferAttempts) {
            check(attempt == 1 || System.nanoTime() - startNs < executionOfferTimeoutNs) { "내부 실행 제출 기한을 초과했습니다: $sequence" }
            val position = cluster.offer(buffer, 0, bytes.size)
            if (position > 0) return
            check(position == Publication.BACK_PRESSURED || position == Publication.ADMIN_ACTION) {
                "내부 실행을 제출할 수 없습니다: sequence=$sequence, result=$position"
            }
            check(attempt < maxExecutionOfferAttempts && System.nanoTime() - startNs < executionOfferTimeoutNs) {
                "내부 실행 제출 재시도 한도를 초과했습니다: $sequence"
            }
            cluster.idleStrategy().idle()
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
            val position = session.offer(buffer, 0, bytes.size)
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
        fail(UnsupportedOperationException("개발 자금 상태의 Snapshot 저장은 지원하지 않습니다"))
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

    override fun doBackgroundWork(nowNs: Long): Int = 0

    override fun onTerminate(cluster: Cluster) {
        started = false
    }
}
