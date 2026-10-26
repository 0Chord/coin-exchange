package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.TradingAdmissionResult
import com.exchange.core.api.trading.application.TradingOrderGateway
import com.exchange.core.api.trading.application.TradingOrderSubmission
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageException
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import io.aeron.Publication
import org.agrona.concurrent.SleepingMillisIdleStrategy
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 연결은 한 스레드가 소유한다. HTTP 동시 요청은 bounded 대기와 최초 요청 키로 연결한다. */
internal class AeronOrderGateway(
    private val settings: AeronGatewaySettings,
    private val markets: DevelopmentMarketRegistry,
    private val openTransport: () -> GatewayOrderTransport = { AeronOrderTransport(settings) },
) : TradingOrderGateway,
    AutoCloseable {
    private data class RequestKey(
        val userId: String,
        val requestId: String,
    )

    private class Pending(
        val submission: TradingOrderSubmission,
        val bytes: ByteArray,
        val enqueuedAt: Long,
        val result: CompletableFuture<TradingAdmissionResult> = CompletableFuture(),
        var offered: Boolean = false,
    )

    private val logger = LoggerFactory.getLogger(AeronOrderGateway::class.java)
    private val lock = Any()
    private val requests = linkedMapOf<RequestKey, Pending>()
    private val queue = ArrayDeque<Pending>()
    private val codec = AdmissionCommandCodec()
    private val ready = CountDownLatch(1)
    private val startupFailure = AtomicReference<Throwable?>()
    private var inFlight = 0
    private var available = false
    private var closing = false
    private val timeoutNs = TimeUnit.MILLISECONDS.toNanos(settings.resultTimeoutMs)
    private val worker: Thread

    init {
        Files.createDirectory(settings.runRoot)
        worker =
            Thread(::run, "aeron-order-gateway").apply {
                isDaemon = true
                start()
            }
        if (!ready.await(settings.startupTimeoutMs + 1_000, TimeUnit.MILLISECONDS)) {
            close()
            error("Aeron HTTP gateway 연결 준비 시간이 초과됐습니다")
        }
        startupFailure.get()?.let {
            close()
            throw IllegalStateException("Aeron HTTP gateway 연결을 준비하지 못했습니다", it)
        }
    }

    override fun submit(submission: TradingOrderSubmission): CompletableFuture<TradingAdmissionResult> =
        synchronized(lock) {
            val key = key(submission)
            requests[key]?.let { previous ->
                return@synchronized if (previous.submission == submission) {
                    previous.result
                } else {
                    completed(TradingAdmissionResult.Rejected("REQUEST_IN_FLIGHT_CONTENT_CONFLICT"))
                }
            }
            if (closing || !available) return@synchronized completed(TradingAdmissionResult.NotSubmitted("GATEWAY_UNAVAILABLE"))
            if (queue.size >= settings.queueCapacity) {
                return@synchronized completed(TradingAdmissionResult.NotSubmitted("QUEUE_FULL", overloaded = true))
            }
            // 최초 요청 키의 판단은 노드가 맡는다. 미등록 마켓도 원본을 바꾸지 않고 전달한다.
            // wire의 필수 문맥은 고정 등록값으로 채우지만, 새 미등록 주문은 노드의 마켓 검사에서 거절된다.
            val context = markets[submission.order.marketId] ?: markets.all().first()
            val bytes =
                try {
                    codec.encode(
                        AdmissionCommandPayload(
                            submission.requestId,
                            submission.order,
                            context.market,
                            context.feePolicySnapshot,
                            "HTTP-${UUID.randomUUID()}",
                            Instant.now(),
                        ),
                    )
                } catch (_: AdmissionMessageException) {
                    return@synchronized completed(TradingAdmissionResult.InvalidInput("INVALID_COMMAND"))
                }
            val pending = Pending(submission, bytes, System.nanoTime())
            requests[key] = pending
            queue.addLast(pending)
            pending.result
        }

    private fun run() {
        var transport: GatewayOrderTransport? = null
        val idle = SleepingMillisIdleStrategy(1)
        try {
            transport = openTransport()
            synchronized(lock) { available = !closing }
            ready.countDown()
            while (!synchronized(lock) { closing }) {
                try {
                    val expired = synchronized(lock) { requests.values.any { System.nanoTime() - it.enqueuedAt >= timeoutNs } }
                    if (expired) {
                        // 대기 종료는 취소가 아니다. 이전 session을 닫기 전에는 같은 키를 다른 원본에 재사용하지 않는다.
                        disconnect(TradingAdmissionResult.Unknown("ADMISSION_TIMEOUT", timedOut = true))
                        transport?.close()
                        transport = null
                        clearDisconnected()
                        if (synchronized(lock) { closing }) break
                        transport = reconnect() ?: break
                        synchronized(lock) { available = !closing }
                    }
                    val current = checkNotNull(transport)
                    var work = current.poll(::receive)
                    work += sendQueued(current)
                    idle.idle(work)
                } catch (error: Exception) {
                    logger.warn("Aeron 주문 연결 오류로 대기 결과를 미확인 처리하고 연결을 교체합니다", error)
                    disconnect(TradingAdmissionResult.Unknown("CONNECTION_LOST"))
                    transport?.close()
                    transport = null
                    clearDisconnected()
                    if (synchronized(lock) { closing }) break
                    transport = reconnect() ?: break
                    synchronized(lock) { available = !closing }
                }
            }
        } catch (error: Throwable) {
            logger.error("Aeron HTTP gateway 연결 소유 스레드가 중단됐습니다", error)
            startupFailure.compareAndSet(null, error)
        } finally {
            synchronized(lock) {
                closing = true
                available = false
            }
            ready.countDown()
            disconnect(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"))
            try {
                transport?.close()
            } finally {
                clearDisconnected()
            }
        }
    }

    /** 최초 시작 실패와 달리 교체 연결 실패는 새 요청을 막은 채 종료 요청까지 재시도한다. */
    private fun reconnect(): GatewayOrderTransport? {
        val retryIdle = SleepingMillisIdleStrategy(100)
        while (!synchronized(lock) { closing }) {
            try {
                return openTransport()
            } catch (error: Exception) {
                logger.warn("Aeron 교체 연결을 열지 못해 신규 요청을 차단한 채 재시도합니다", error)
                retryIdle.idle()
            }
        }
        return null
    }

    private fun sendQueued(transport: GatewayOrderTransport): Int =
        synchronized(lock) {
            var count = 0
            while (!closing && queue.isNotEmpty() && inFlight < settings.maxInFlight) {
                val next = queue.first()
                val position = transport.offer(next.bytes)
                if (position == Publication.BACK_PRESSURED || position == Publication.ADMIN_ACTION) break
                check(position > 0) { "Aeron 주문을 제출할 수 없습니다" }
                queue.removeFirst()
                next.offered = true
                inFlight++
                count++
            }
            count
        }

    private fun receive(response: AdmissionResultPayload) {
        val pending =
            synchronized(lock) {
                val key = RequestKey(response.userId, response.requestId)
                val candidate = requests[key] ?: return
                check(
                    candidate.offered &&
                        candidate.submission.order.marketId.value == response.marketId &&
                        candidate.submission.order.orderId.value == response.orderId,
                ) { "Aeron 접수 응답이 전송한 주문과 다릅니다" }
                requests.remove(key)
                inFlight--
                candidate
            }
        val result =
            when (response) {
                is AdmissionResultPayload.Accepted -> TradingAdmissionResult.Accepted(response.sequence)
                is AdmissionResultPayload.Rejected -> TradingAdmissionResult.Rejected(response.reason.name, response.insufficientBalance)
            }
        pending.result.complete(result)
    }

    private fun disconnect(result: TradingAdmissionResult.Unknown) {
        val pending =
            synchronized(lock) {
                available = false
                requests.values.toList()
            }
        pending.forEach { it.result.complete(result) }
    }

    private fun clearDisconnected() {
        synchronized(lock) {
            requests.clear()
            queue.clear()
            inFlight = 0
        }
    }

    override fun close() {
        synchronized(lock) {
            closing = true
            available = false
        }
        if (Thread.currentThread() !== worker) {
            worker.join(settings.startupTimeoutMs + 2_000)
            check(!worker.isAlive) { "Aeron HTTP gateway 연결 소유 스레드가 종료되지 않았습니다" }
        }
    }

    private fun key(submission: TradingOrderSubmission): RequestKey = RequestKey(submission.order.userId.value, submission.requestId)

    private fun completed(result: TradingAdmissionResult): CompletableFuture<TradingAdmissionResult> =
        CompletableFuture.completedFuture(result)
}
