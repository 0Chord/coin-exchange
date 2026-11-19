package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.BoundedTradingCancellation
import com.exchange.core.api.trading.application.BoundedTradingOrderGateway
import com.exchange.core.api.trading.application.BoundedTradingOrderSubmission
import com.exchange.core.api.trading.application.BoundedTradingResult
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.recovery.RecoveryDurabilityGate
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import io.aeron.Publication
import org.agrona.concurrent.UnsafeBuffer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class BoundedGatewayException(
    val code: String,
    val possiblySubmitted: Boolean,
) : IllegalStateException(code)

/** HTTP 경계의 송수신은 선택한 wire와 무관하게 연결 소유 스레드 하나가 담당한다. */
internal interface BoundedGatewayTransport : AutoCloseable {
    val leadershipTermId: Long? get() = null

    fun offer(bytes: ByteArray): Long

    fun poll(): Int
}

/** 분리 취소 응답과 기존 제어 응답은 각자의 계약으로 대기 요청과 대조한다. */
internal class BoundedGatewayReplies(
    private val control: (BoundedTradingReply) -> Unit,
    val cancellation: (SplitOrderDeliveryReply) -> Unit,
) : (BoundedTradingReply) -> Unit {
    override fun invoke(reply: BoundedTradingReply) = control(reply)
}

internal class BoundedAeronGateway(
    private val settings: AeronGatewaySettings,
    private val protectedLineage: ClusterLineage? = null,
    private val durabilityGate: RecoveryDurabilityGate? = null,
    private val commandEncoder: (Long, BoundedTradingCommand) -> ByteArray = BoundedTradingCodec()::encode,
    private val cancelEncoder: ((Long, SplitOrderDeliveryCommand.Cancel) -> ByteArray)? = null,
    private val recoveryGate: HttpOrderRecoveryGate? = null,
    private val openTransport: (BoundedGatewayReplies) -> BoundedGatewayTransport = { receive ->
        val client = BoundedAeronClient(settings, receive)
        object : BoundedGatewayTransport {
            override fun offer(bytes: ByteArray): Long = client.connection.offer(UnsafeBuffer(bytes), 0, bytes.size)

            override fun poll(): Int = client.poll()

            override fun close() = client.close()
        }
    },
) : AutoCloseable,
    BoundedTradingOrderGateway {
    private sealed class Pending<T>(
        val deadline: Long,
    ) {
        val result = CompletableFuture<T>()
        var correlation = 0L
        var bytes: ByteArray? = null
        var recoveryApproval: HttpOrderRecoveryGate.State? = null
        val terminal = AtomicBoolean()

        val requiresRecoveryApproval: Boolean
            get() = this is Cancellation || (this is Control && command is BoundedTradingCommand.Admit)

        fun isExpired(): Boolean = System.nanoTime() - deadline >= 0

        class Control(
            val command: BoundedTradingCommand,
            deadline: Long,
        ) : Pending<BoundedTradingReply>(deadline)

        class Cancellation(
            val command: SplitOrderDeliveryCommand.Cancel,
            deadline: Long,
        ) : Pending<SplitOrderDeliveryReply>(deadline)
    }

    private val queue = ArrayBlockingQueue<Pending<*>>(settings.queueCapacity)
    private val inFlight = linkedMapOf<Long, Pending<*>>()
    private val protectionWaiting = ConcurrentHashMap<Long, Pending<*>>()
    private val startup = CountDownLatch(1)
    private val startupFailure = AtomicReference<Throwable?>()
    private val shutdownFailure = AtomicReference<Throwable?>()
    private val completionContext = ThreadLocal<Boolean>()
    private val logger = LoggerFactory.getLogger(BoundedAeronGateway::class.java)
    private val admissionLock = Any()
    private val completionPermits = Semaphore(settings.queueCapacity + settings.maxInFlight)
    private val completions = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("bounded-http-completion-", 0).factory())

    @Volatile private var closing = false

    @Volatile private var available = false

    @Volatile private var observedFundsTerm: Long? = null

    private var nextCorrelation = 1L
    private val worker: Thread
    val protectionProfile: String get() = if (durabilityGate == null) "LOCAL_QUORUM" else "REMOTE_RECOVERY"

    init {
        require(
            (protectedLineage == null) == (durabilityGate == null),
        ) { "protected gateway requires lineage and a real durability gate together" }
        Files.createDirectory(settings.runRoot)
        worker =
            Thread(::run, "bounded-aeron-gateway").apply {
                isDaemon = true
                start()
            }
        if (!startup.await(settings.startupTimeoutMs + 1_000, TimeUnit.MILLISECONDS)) {
            close()
            error("bounded gateway startup timed out")
        }
        startupFailure.get()?.let {
            close()
            throw IllegalStateException("bounded gateway unavailable", it)
        }
    }

    override fun submit(submission: BoundedTradingOrderSubmission): CompletableFuture<BoundedTradingResult> =
        submit(
            BoundedTradingCommand.Admit(
                RequestToken(UserId(submission.userId), submission.channel, submission.epoch, submission.sequence),
                OrderIntent(
                    MarketId(submission.marketId),
                    submission.expectedConfigurationVersion,
                    submission.side,
                    submission.orderType,
                    submission.timeInForce,
                    Price(submission.price),
                    Quantity(submission.quantity),
                ),
            ),
        ).handle(::applicationResult)

    override fun cancel(cancellation: BoundedTradingCancellation): CompletableFuture<BoundedTradingResult> {
        val token = RequestToken(UserId(cancellation.userId), cancellation.channel, cancellation.epoch, cancellation.sequence)
        val intent = CancelIntent(MarketId(cancellation.marketId), OrderId(cancellation.orderId), cancellation.expectedConfigurationVersion)
        if (cancelEncoder == null) {
            return CompletableFuture.completedFuture(
                BoundedTradingResult("UNSUPPORTED_COMMAND", reason = "UNSUPPORTED_COMMAND", protection = protectionProfile),
            )
        }
        val command = SplitOrderDeliveryCommand.Cancel(token, intent)
        return enqueue(Pending.Cancellation(command, requestDeadline())).handle { reply, failure ->
            if (failure != null) return@handle failedResult(failure)
            val response = checkNotNull(reply)
            val receipt = response.cancellation
            BoundedTradingResult(
                code = response.code,
                reason = if (receipt == null) response.code else receipt.reason,
                appliedLogPosition = response.appliedLogPosition,
                protection = protectionProfile,
                cancellationCode = receipt?.code?.name,
                cancelId = receipt?.cancelId,
            )
        }
    }

    override fun acknowledge(
        userId: String,
        channel: Int,
        epoch: Long,
        through: Long,
    ): CompletableFuture<BoundedTradingResult> =
        submit(BoundedTradingCommand.Acknowledge(UserId(userId), channel, epoch, through)).handle(::applicationResult)

    override fun rotate(
        userId: String,
        channel: Int,
        expectedEpoch: Long,
    ): CompletableFuture<BoundedTradingResult> =
        submit(BoundedTradingCommand.Rotate(UserId(userId), channel, expectedEpoch)).handle(::applicationResult)

    private fun applicationResult(
        reply: BoundedTradingReply?,
        failure: Throwable?,
    ): BoundedTradingResult {
        if (failure != null) return failedResult(failure)
        val value = checkNotNull(reply)
        val receipt = value.admission
        return BoundedTradingResult(
            code = value.code,
            admissionCode = receipt?.code?.name,
            orderId = receipt?.orderId?.value,
            admissionSequence = receipt?.admissionSequence,
            marketAdmissionSequence = receipt?.marketAdmissionSequence,
            reason =
                receipt?.reason?.name ?: value.code.takeIf {
                    receipt == null && value.code !in setOf("ACKNOWLEDGED", "ISSUED", "ALREADY_ISSUED")
                },
            epoch = value.epoch,
            retiredThrough = value.retiredThrough,
            appliedLogPosition = value.appliedLogPosition,
            protection = protectionProfile,
        )
    }

    private fun failedResult(failure: Throwable): BoundedTradingResult {
        val cause = if (failure is CompletionException) failure.cause else failure
        val gatewayError = cause as? BoundedGatewayException
        val possiblySubmitted = gatewayError?.possiblySubmitted == true
        val failureCode = gatewayError?.code ?: "GATEWAY_UNAVAILABLE"
        return BoundedTradingResult(
            code = if (possiblySubmitted) "OUTCOME_UNKNOWN" else "NOT_SUBMITTED",
            reason = failureCode,
            protection = protectionProfile,
            failureCode = failureCode,
            possiblySubmitted = possiblySubmitted,
        )
    }

    fun submit(command: BoundedTradingCommand): CompletableFuture<BoundedTradingReply> {
        require(
            command is BoundedTradingCommand.Admit || command is BoundedTradingCommand.Acknowledge ||
                command is BoundedTradingCommand.Rotate,
        ) {
            "HTTP gateway does not expose cluster initialization, execution or quarantine controls"
        }
        return enqueue(Pending.Control(command, requestDeadline()))
    }

    private fun requestDeadline(): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.resultTimeoutMs)

    private fun <T> enqueue(pending: Pending<T>): CompletableFuture<T> {
        synchronized(admissionLock) {
            val recovery = if (recoveryGate != null && pending.requiresRecoveryApproval) recoveryGate.current else null
            if (closing || !available) {
                pending.result.completeExceptionally(BoundedGatewayException("GATEWAY_UNAVAILABLE", false))
            } else if (recovery != null && !recovery.isReadyFor(observedFundsTerm)) {
                pending.result.completeExceptionally(BoundedGatewayException(recovery.refusalReason, false))
            } else if (!completionPermits.tryAcquire()) {
                pending.result.completeExceptionally(BoundedGatewayException("QUEUE_FULL", false))
            } else {
                pending.recoveryApproval = recovery
                if (!queue.offer(pending)) {
                    completionPermits.release()
                    pending.result.completeExceptionally(BoundedGatewayException("QUEUE_FULL", false))
                }
            }
        }
        return pending.result
    }

    private fun receiveControl(response: BoundedTradingReply) {
        val pending = inFlight[response.correlationId] as? Pending.Control ?: return
        receiveReply(pending, response.lineage, response.appliedLogPosition, response)
    }

    private fun receiveCancellation(response: SplitOrderDeliveryReply) {
        val pending = inFlight[response.correlationId] as? Pending.Cancellation ?: return
        val receipt = response.cancellation
        if (receipt != null && receipt.targetOrderId != pending.command.intent.targetOrderId) return
        receiveReply(pending, response.lineage, response.appliedLogPosition, response)
    }

    private fun <T> receiveReply(
        pending: Pending<T>,
        lineage: ClusterLineage?,
        logPosition: Long,
        response: T,
    ) {
        val correlation = pending.correlation
        inFlight.remove(correlation)
        if (pending.isExpired()) {
            complete(pending, null, BoundedGatewayException("RESULT_UNCONFIRMED", true))
            return
        }
        if (durabilityGate == null) {
            complete(pending, response, null)
            return
        }
        if (lineage != protectedLineage) {
            complete(pending, null, BoundedGatewayException("PROTECTION_LINEAGE_MISMATCH", true))
            return
        }
        protectionWaiting[correlation] = pending
        try {
            durabilityGate
                .awaitProtected(checkNotNull(protectedLineage), logPosition)
                .thenApply { Unit }
                .toCompletableFuture()
                .orTimeout(maxOf(0L, pending.deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                .whenComplete { _, failure ->
                    protectionWaiting.remove(correlation, pending)
                    if (failure == null && !pending.isExpired()) {
                        complete(pending, response, null)
                    } else {
                        complete(pending, null, BoundedGatewayException("REMOTE_PROTECTION_UNCONFIRMED", true))
                    }
                }
        } catch (_: Exception) {
            protectionWaiting.remove(correlation, pending)
            complete(pending, null, BoundedGatewayException("REMOTE_PROTECTION_UNCONFIRMED", true))
        }
    }

    private fun run() {
        var client: BoundedGatewayTransport? = null
        val idle = TradingAeronRuntime.idleStrategy()
        try {
            client = openTransport(BoundedGatewayReplies(::receiveControl, ::receiveCancellation))
            observeFundsTerm(client)
            available = true
            startup.countDown()
            while (!closing) {
                var work = client.poll()
                observeFundsTerm(client)
                val now = System.nanoTime()
                val iterator = inFlight.values.iterator()
                while (iterator.hasNext()) {
                    val pending = iterator.next()
                    if (now - pending.deadline >= 0) {
                        iterator.remove()
                        complete(pending, null, BoundedGatewayException("RESULT_UNCONFIRMED", true))
                        work++
                    }
                }
                while (inFlight.size < settings.maxInFlight) {
                    val pending = queue.peek() ?: break
                    if (pending.bytes == null && !pending.isExpired()) {
                        pending.correlation = nextCorrelation.also { nextCorrelation = Math.addExact(it, 1) }
                        // 외부 입력의 인코딩 실패만 이 요청의 실패로 처리한다. 카운터·전송·내부 오류는
                        // 계속 바깥으로 전파해 소유자를 중단시킨다.
                        try {
                            pending.bytes =
                                when (pending) {
                                    is Pending.Control -> commandEncoder(pending.correlation, pending.command)
                                    is Pending.Cancellation -> checkNotNull(cancelEncoder)(pending.correlation, pending.command)
                                }
                        } catch (_: IllegalArgumentException) {
                            check(queue.poll() === pending) { "gateway queue owner changed" }
                            complete(pending, null, BoundedGatewayException("INVALID_REQUEST", false))
                            work++
                            continue
                        }
                    }
                    if (pending.isExpired()) {
                        queue.poll()
                        complete(pending, null, BoundedGatewayException("QUEUE_TIMEOUT", false))
                        work++
                        continue
                    }
                    val bytes = checkNotNull(pending.bytes)
                    val approval = pending.recoveryApproval
                    val offered =
                        if (approval == null) {
                            client.offer(bytes)
                        } else {
                            checkNotNull(recoveryGate).tryOffer(approval, client, bytes)
                        }
                    // NOT_CONNECTED는 다음 poll을 기다리기 전에 Aeron 송신 경로를 닫을 수 있다.
                    if (offered == null || offered < 0) observeFundsTerm(client)
                    if (offered == null) {
                        queue.poll()
                        val refusalReason = checkNotNull(recoveryGate).current.refusalReason
                        complete(pending, null, BoundedGatewayException(refusalReason, false))
                        work++
                        continue
                    }
                    if (offered > 0) {
                        queue.poll()
                        inFlight[pending.correlation] = pending
                        work++
                    } else {
                        check(
                            offered == Publication.BACK_PRESSURED || offered == Publication.ADMIN_ACTION ||
                                offered == Publication.NOT_CONNECTED,
                        ) { "bounded transport closed: $offered" }
                        break
                    }
                }
                idle.idle(work)
            }
        } catch (error: Throwable) {
            startupFailure.compareAndSet(null, error)
        } finally {
            try {
                synchronized(admissionLock) { available = false }
                startup.countDown()
                inFlight.values.forEach { complete(it, null, BoundedGatewayException("RESULT_UNCONFIRMED", true)) }
                inFlight.clear()
                protectionWaiting.values.forEach { complete(it, null, BoundedGatewayException("REMOTE_PROTECTION_UNCONFIRMED", true)) }
                protectionWaiting.clear()
                while (true) {
                    val pending =
                        queue.poll() ?: break
                    complete(pending, null, BoundedGatewayException("GATEWAY_UNAVAILABLE", false))
                }
            } finally {
                try {
                    client?.close()
                } finally {
                    stopCompletions()
                }
            }
        }
    }

    private fun observeFundsTerm(client: BoundedGatewayTransport) {
        if (recoveryGate == null) return
        val leadershipTerm = client.leadershipTermId
        if (observedFundsTerm != leadershipTerm) observedFundsTerm = leadershipTerm
    }

    private fun <T> complete(
        pending: Pending<T>,
        response: T?,
        error: Throwable?,
    ) {
        if (!pending.terminal.compareAndSet(false, true)) return
        try {
            completions.execute {
                completeFuture(pending, response, error)
            }
        } catch (_: RejectedExecutionException) {
            // 원격 보호 완료와 종료가 경합해도 최초 결과와 예약한 완료 슬롯을 잃지 않는다.
            completeFuture(pending, response, error)
        }
    }

    private fun <T> completeFuture(
        pending: Pending<T>,
        response: T?,
        error: Throwable?,
    ) {
        completionContext.set(true)
        try {
            if (error != null) pending.result.completeExceptionally(error) else pending.result.complete(checkNotNull(response))
        } finally {
            completionContext.remove()
            completionPermits.release()
        }
    }

    /** 전송 소유자가 마지막 결과를 제출한 뒤 정리하며, 멈춘 콜백을 무한히 기다리지 않는다. */
    private fun stopCompletions() {
        completions.shutdown()
        try {
            if (!completions.awaitTermination(settings.startupTimeoutMs + 1_000, TimeUnit.MILLISECONDS)) {
                val failure = IllegalStateException("Bounded Aeron 완료 callback의 종료 대기 시간이 초과됐습니다")
                shutdownFailure.compareAndSet(null, failure)
                completions.shutdownNow()
                completions.awaitTermination(250, TimeUnit.MILLISECONDS)
                logger.warn(failure.message)
            }
        } catch (error: InterruptedException) {
            shutdownFailure.compareAndSet(null, error)
            completions.shutdownNow()
            Thread.currentThread().interrupt()
            logger.warn("Bounded Aeron 완료 callback의 종료 대기가 중단됐습니다", error)
        }
    }

    override fun close() {
        synchronized(admissionLock) {
            closing = true
            available = false
        }
        // 동기 완료 콜백은 자기 실행기의 종료나 그것을 기다리는 소유 스레드와 서로 기다리지 않는다.
        if (Thread.currentThread() !== worker && completionContext.get() != true) {
            worker.join(settings.startupTimeoutMs + settings.resultTimeoutMs + 2_000)
            check(!worker.isAlive) { "bounded gateway did not stop" }
            shutdownFailure.get()?.let { throw IllegalStateException("Bounded Aeron 완료 처리를 종료하지 못했습니다", it) }
        }
    }
}
