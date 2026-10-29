package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import io.aeron.Publication
import java.nio.file.Files
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

internal const val DIRECT_AERON_SUSTAINED_MAX_ORDERS = 600_000

/** 고정 60초 계획의 유한 guard이며 자원 적합성·할당 byte 상한은 아니다. */
internal fun directAeronSustainedCount(
    rate: Int,
    seconds: Int,
): Int {
    require(rate in 1..10_000 && seconds == 60) { "sustained는 rate=1..10000, seconds=60만 허용합니다" }
    return Math.multiplyExact(rate, seconds).also { require(it in 1..DIRECT_AERON_SUSTAINED_MAX_ORDERS) }
}

/** 금융 이력과 완료 관측은 이 발생기가 소유하지 않는다. 장기는 명시적 60초 계획만 허용한다. */
internal data class DirectAeronCapacityWorkload(
    val rate: Int,
    val count: Int,
    val prefix: String,
    val side: Side,
    val userId: String,
    val maxInFlight: Int = 256,
    val marketId: MarketId = MarketId("BTC-USDT"),
    val price: Long = 10_000,
    val quantity: Long = 100_000_000,
    val sustainedSeconds: Int? = null,
) {
    init {
        require(rate in 1..20_000 && maxInFlight in 1..4096)
        if (sustainedSeconds == null) {
            require(count in 1..2000)
        } else {
            require(count == directAeronSustainedCount(rate, sustainedSeconds)) { "sustained N은 rate*seconds여야 합니다" }
            require(maxInFlight == 4096 && prefix == "M-" && side == Side.BUY && userId == "BUYER")
            require(marketId == MarketId("BTC-USDT") && price == 10_000L && quantity == 100_000_000L)
        }
        require(prefix.matches(Regex("[A-Za-z0-9-]{1,40}")))
        require(userId.isNotBlank() && userId.codePointCount(0, userId.length) <= 64 && '\u0000' !in userId)
        require(price > 0 && quantity > 0)
    }

    val windowNs: Long get() = TimeUnit.SECONDS.toNanos(count.toLong()) / rate
    val sustained: Boolean get() = sustainedSeconds != null
}

internal enum class DirectAeronCapacityOutcome {
    NOT_SENT,
    OFFER_UNKNOWN,
    RESULT_UNKNOWN,
    ACCEPTED,
    REJECTED,
}

/** 모든 nanoTime은 이 run을 소유한 부하 JVM의 시각이다. null은 관측하지 못한 값이다. */
internal data class DirectAeronCapacitySample(
    val command: AdmissionCommandPayload,
    val encodedCommand: ByteArray?,
    val dueNs: Long,
    val startNs: Long?,
    val deadlineNs: Long?,
    val firstOfferNs: Long?,
    val transportAcceptedNs: Long?,
    val transportPosition: Long?,
    val lastOfferResult: Long?,
    val offerAttempts: Int,
    val ackNs: Long?,
    val response: AdmissionResultPayload?,
    val outcome: DirectAeronCapacityOutcome,
    val reason: String?,
) {
    val transportAccepted: Boolean?
        get() = if (outcome == DirectAeronCapacityOutcome.OFFER_UNKNOWN) null else transportAcceptedNs != null
    val generatorDelayNs: Long? get() = startNs?.minus(dueNs)
    val ackRttNs: Long?
        get() =
            if (outcome in
                setOf(DirectAeronCapacityOutcome.ACCEPTED, DirectAeronCapacityOutcome.REJECTED)
            ) {
                ackNs?.minus(checkNotNull(startNs))
            } else {
                null
            }
    val scheduledAckLatencyNs: Long? get() = ackRttNs?.let { checkNotNull(ackNs) - dueNs }
}

/** 양수 offer 개수와 유효 접수 응답 개수를 구분한다. 실행 완료나 정산 TPS를 뜻하지 않는다. */
internal data class DirectAeronCapacityResult(
    val workload: DirectAeronCapacityWorkload,
    val firstDueNs: Long,
    val windowEndNs: Long,
    val finishedNs: Long,
    val planned: Int,
    val started: Int,
    val notSent: Int,
    val transportAccepted: Int,
    val offerUnknown: Int,
    val resultUnknown: Int,
    val accepted: Int,
    val rejected: Int,
    val peakInFlight: Int,
    val duplicateResponses: Long,
    val loadFailure: String?,
    val closeFailure: String?,
    val errors: List<String>,
    val errorOverflow: Long,
    val samples: List<DirectAeronCapacitySample>,
)

/** 부하 JVM 시계 하나에서만 계산한다. ACK의 창 뒤 도착은 drain이며 창 안 TPS에 더하지 않는다. */
internal data class DirectAeronCapacityWindowEvidence(
    val plannedWindowNs: Long,
    val windowElapsed: Boolean,
    val finishedRelativeToWindowEndNs: Long,
    val startedInsideWindow: Int,
    val startedAfterWindow: Int,
    val positiveOfferInsideWindow: Int,
    val positiveOfferAfterWindow: Int,
    val acceptedAckInsideWindow: Int,
    val acceptedAckAfterWindow: Int,
    val observedAckInsideWindow: Int,
    val observedAckAfterWindow: Int,
    val notSent: Int,
    val offerUnknown: Int,
    val resultUnknown: Int,
    val allPlannedSendsInsideWindow: Boolean,
    val allAcceptedAckInsideWindow: Boolean,
)

internal fun directAeronCapacityWindowEvidence(result: DirectAeronCapacityResult): DirectAeronCapacityWindowEvidence {
    val windowNs = result.windowEndNs - result.firstDueNs
    require(windowNs == result.workload.windowNs && windowNs > 0)

    fun inside(ns: Long?): Boolean = ns != null && ns - result.firstDueNs >= 0 && result.windowEndNs - ns > 0

    fun after(ns: Long?): Boolean = ns != null && ns - result.windowEndNs >= 0
    val started = result.samples.count { inside(it.startNs) }
    val offered = result.samples.count { inside(it.transportAcceptedNs) }
    val acceptedAck = result.samples.count { it.outcome == DirectAeronCapacityOutcome.ACCEPTED && inside(it.ackNs) }
    return DirectAeronCapacityWindowEvidence(
        windowNs,
        result.finishedNs - result.firstDueNs >= windowNs,
        result.finishedNs - result.windowEndNs,
        started,
        result.samples.count { after(it.startNs) },
        offered,
        result.samples.count { after(it.transportAcceptedNs) },
        acceptedAck,
        result.samples.count { it.outcome == DirectAeronCapacityOutcome.ACCEPTED && after(it.ackNs) },
        result.samples.count { inside(it.ackNs) },
        result.samples.count { after(it.ackNs) },
        result.notSent,
        result.offerUnknown,
        result.resultUnknown,
        started == result.planned && offered == result.planned,
        acceptedAck == result.planned,
    )
}

/**
 * run 호출 스레드 하나가 송수신을 소유한다. 기본 transport는 제품의 실제 AeronCluster와
 * 전용 MediaDriver 하나를 사용하고 poll 안에서 keepalive를 처리한다. 자동 재연결·재제출은 없다.
 */
internal class DirectAeronCapacityLoad(
    private val config: DevelopmentFundingSettings,
    private val workload: DirectAeronCapacityWorkload,
    private val openTransport: () -> GatewayOrderTransport = {
        Files.createDirectory(config.runRoot)
        AeronOrderTransport(
            AeronGatewaySettings(
                config.runRoot,
                config.ingressEndpoints,
                config.endpoint(7),
                maxInFlight = workload.maxInFlight,
                resultTimeoutMs = config.resultTimeoutMs,
                startupTimeoutMs = config.startupTimeoutMs,
            ),
        )
    },
    private val nanoTime: () -> Long = System::nanoTime,
    private val parkNanos: (Long) -> Unit = { LockSupport.parkNanos(it) },
) {
    private class Request(
        val command: AdmissionCommandPayload,
    ) {
        var bytes: ByteArray? = null
        var dueNs = 0L
        var startNs: Long? = null
        var deadlineNs: Long? = null
        var firstOfferNs: Long? = null
        var transportAcceptedNs: Long? = null
        var transportPosition: Long? = null
        var lastOfferResult: Long? = null
        var offerAttempts = 0
        var ackNs: Long? = null
        var response: AdmissionResultPayload? = null
        var outcome: DirectAeronCapacityOutcome? = null
        var reason: String? = null
        var offerUnknown = false

        fun sample(): DirectAeronCapacitySample =
            DirectAeronCapacitySample(
                command,
                bytes?.copyOf(),
                dueNs,
                startNs,
                deadlineNs,
                firstOfferNs,
                transportAcceptedNs,
                transportPosition,
                lastOfferResult,
                offerAttempts,
                ackNs,
                response,
                checkNotNull(outcome),
                reason,
            )
    }

    private val used = AtomicBoolean()

    /** 연결이 준비된 뒤 기본 예정 시각을 정한다. 테스트는 같은 부하 시계의 명시 시각을 넣는다. */
    fun run(requestedFirstDueNs: Long? = null): DirectAeronCapacityResult {
        check(used.compareAndSet(false, true)) { "직접 Aeron 부하 발생기는 실행당 한 번만 사용할 수 있습니다" }
        val context = requireNotNull(config.markets[workload.marketId]) { "등록되지 않은 부하 마켓입니다" }
        val requests =
            List(workload.count) { index ->
                val id = "${workload.prefix}$index"
                Request(
                    AdmissionCommandPayload(
                        id,
                        OrderAdmissionInput(
                            workload.marketId,
                            OrderId(id),
                            UserId(workload.userId),
                            workload.side,
                            OrderType.LIMIT,
                            TimeInForce.GTC,
                            Price(workload.price),
                            Quantity(workload.quantity),
                        ),
                        context.market,
                        context.feePolicySnapshot,
                        "DIRECT-${UUID.randomUUID()}",
                        Instant.now(),
                    ),
                )
            }
        val byRequestId = requests.associateBy { it.command.requestId }
        // Starts append once; fixed timeout clamped to one drain end keeps deadline order.
        val pending = linkedMapOf<String, Request>()
        val unoffered = ArrayDeque<Request>()
        val codec = AdmissionCommandCodec()
        val errors = mutableListOf<String>()
        var errorOverflow = 0L
        var duplicates = 0L
        var peakInFlight = 0
        var transport: GatewayOrderTransport? = null
        var firstDueNs = requestedFirstDueNs ?: 0L
        var scheduleAssigned = false
        var terminalReason: String? = null
        var loadFailure: String? = null
        var closeFailure: String? = null

        fun recordError(reason: String) {
            if (errors.size < 2000) errors.add(reason.take(16_384)) else errorOverflow++
        }

        fun assignSchedule(firstDue: Long) {
            firstDueNs = firstDue
            requests.forEachIndexed { index, request -> request.dueNs = firstDue + index.toLong() * 1_000_000_000L / workload.rate }
            scheduleAssigned = true
        }

        fun unresolved(
            request: Request,
            reason: String,
        ) {
            if (request.outcome != null) return
            request.outcome =
                when {
                    request.transportAcceptedNs != null -> DirectAeronCapacityOutcome.RESULT_UNKNOWN
                    request.offerUnknown -> DirectAeronCapacityOutcome.OFFER_UNKNOWN
                    else -> DirectAeronCapacityOutcome.NOT_SENT
                }
            request.reason = reason
            pending.remove(request.command.requestId)
            // Timeout removes an oldest active request; offer failure removes the offer head.
            if (unoffered.peekFirst() === request) unoffered.removeFirst()
        }

        fun receive(response: AdmissionResultPayload) {
            val receivedNs = nanoTime()
            val request = byRequestId[response.requestId]
            if (request == null || response.userId != request.command.order.userId.value ||
                response.marketId != request.command.order.marketId.value || response.orderId != request.command.order.orderId.value
            ) {
                recordError("RESPONSE_IDENTITY_MISMATCH:$response")
                return
            }
            if (request.transportAcceptedNs == null) {
                recordError("RESPONSE_WITHOUT_POSITIVE_OFFER:${response.requestId}")
                return
            }
            if (response is AdmissionResultPayload.Accepted && response.sequence <= 0) {
                recordError("INVALID_ADMISSION_SEQUENCE:${response.requestId}")
                return
            }
            request.response?.let { previous ->
                if (previous == response) duplicates++ else recordError("RESPONSE_CONTENT_CONFLICT:${response.requestId}")
                return
            }
            check(receivedNs - checkNotNull(request.transportAcceptedNs) >= 0) { "응답 부하 시계가 전송 수용 이전입니다" }
            request.response = response
            request.ackNs = receivedNs
            if (request.outcome != null || receivedNs - checkNotNull(request.deadlineNs) >= 0) {
                unresolved(request, "ACK_AFTER_DEADLINE")
                return
            }
            request.outcome =
                if (response is AdmissionResultPayload.Accepted) {
                    DirectAeronCapacityOutcome.ACCEPTED
                } else {
                    DirectAeronCapacityOutcome.REJECTED
                }
            pending.remove(response.requestId)
        }

        try {
            transport = openTransport()
            assignSchedule(requestedFirstDueNs ?: (nanoTime() + TimeUnit.SECONDS.toNanos(1)))
            val windowEndNs = firstDueNs + workload.windowNs
            val drainEndNs = windowEndNs + TimeUnit.SECONDS.toNanos(60)
            val timeoutNs = TimeUnit.MILLISECONDS.toNanos(config.resultTimeoutMs)
            var next = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("직접 Aeron 부하가 중단됐습니다")
                var work = transport.poll(::receive)
                var now = nanoTime()
                if (now - drainEndNs >= 0) {
                    terminalReason = "RUN_DEADLINE"
                    break
                }
                val deadlines = pending.values.iterator()
                while (deadlines.hasNext()) {
                    val request = deadlines.next()
                    if (now - checkNotNull(request.deadlineNs) < 0) break
                    // Remove via this iterator before unresolved's now-idempotent map removal.
                    deadlines.remove()
                    unresolved(request, "REQUEST_DEADLINE")
                }
                if (next < requests.size && now - requests[next].dueNs >= 0) {
                    if (now - drainEndNs >= 0) {
                        terminalReason = "RUN_DEADLINE"
                        break
                    }
                    val request = requests[next++]
                    if (workload.sustained && now - windowEndNs >= 0) {
                        unresolved(request, "SUSTAINED_SCHEDULE_WINDOW_ENDED")
                    } else if (pending.size == workload.maxInFlight) {
                        unresolved(request, "GENERATOR_SATURATED")
                    } else {
                        request.startNs = now
                        request.deadlineNs = now + minOf(timeoutNs, drainEndNs - now)
                        request.bytes = codec.encode(request.command)
                        pending[request.command.requestId] = request
                        unoffered.addLast(request)
                        peakInFlight = maxOf(peakInFlight, pending.size)
                    }
                    work++
                    now = nanoTime()
                }
                if (terminalReason != null) break
                // 한 poll 사이의 offer 수도 pending 상한을 넘지 않는다. ACK를 기다리며 직렬화하지 않는다.
                while (unoffered.isNotEmpty()) {
                    val request = unoffered.peekFirst()
                    if (request.outcome != null || request.transportAcceptedNs != null) {
                        unoffered.removeFirst()
                        continue
                    }
                    now = nanoTime()
                    if (workload.sustained && now - windowEndNs >= 0) {
                        unresolved(request, "SUSTAINED_OFFER_WINDOW_ENDED")
                        continue
                    }
                    if (now - checkNotNull(request.deadlineNs) >= 0) {
                        unresolved(request, "OFFER_DEADLINE")
                        continue
                    }
                    request.firstOfferNs = request.firstOfferNs ?: now
                    request.offerAttempts++
                    val position =
                        try {
                            transport.offer(checkNotNull(request.bytes))
                        } catch (failure: Exception) {
                            request.offerUnknown = true
                            throw failure
                        }
                    request.lastOfferResult = position
                    if (position > 0) {
                        request.transportAcceptedNs = nanoTime()
                        request.transportPosition = position
                        unoffered.removeFirst()
                        work++
                    } else if (position == Publication.BACK_PRESSURED || position == Publication.ADMIN_ACTION) {
                        if (request.offerAttempts >= config.maxReplyAttempts) unresolved(request, "OFFER_ATTEMPTS_EXHAUSTED")
                        break
                    } else {
                        terminalReason =
                            when (position) {
                                Publication.NOT_CONNECTED -> "NOT_CONNECTED"
                                Publication.CLOSED -> "CLOSED"
                                Publication.MAX_POSITION_EXCEEDED -> "MAX_POSITION_EXCEEDED"
                                else -> "UNSUPPORTED_OFFER_RESULT:$position"
                            }
                        recordError("TERMINAL_OFFER:$terminalReason")
                        break
                    }
                }
                if (terminalReason != null) break
                now = nanoTime()
                if (next == requests.size && pending.isEmpty() && now - windowEndNs >= 0) break
                if (work == 0) {
                    val untilNext = if (next < requests.size) requests[next].dueNs - now else 100_000L
                    val untilWindowEnd = if (now - windowEndNs < 0) windowEndNs - now else 100_000L
                    parkNanos(minOf(100_000L, untilNext.coerceAtLeast(1), untilWindowEnd.coerceAtLeast(1)))
                }
            }
        } catch (failure: Exception) {
            terminalReason = "CONNECTION_OR_LOAD_FAILURE:${failure.javaClass.simpleName}"
            loadFailure = failure.stackTraceToString()
            recordError(checkNotNull(loadFailure))
            if (failure is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            if (!scheduleAssigned) assignSchedule(requestedFirstDueNs ?: nanoTime())
            requests.forEach { unresolved(it, terminalReason ?: "RUN_ENDED_WITHOUT_RESULT") }
            try {
                transport?.close()
            } catch (failure: Exception) {
                closeFailure = failure.stackTraceToString()
                recordError("TRANSPORT_CLOSE_FAILURE:$closeFailure")
            }
        }
        val samples = requests.map(Request::sample)
        return DirectAeronCapacityResult(
            workload,
            firstDueNs,
            firstDueNs + workload.windowNs,
            nanoTime(),
            samples.size,
            samples.count { it.startNs != null },
            samples.count { it.outcome == DirectAeronCapacityOutcome.NOT_SENT },
            samples.count { it.transportAcceptedNs != null },
            samples.count { it.outcome == DirectAeronCapacityOutcome.OFFER_UNKNOWN },
            samples.count { it.outcome in setOf(DirectAeronCapacityOutcome.OFFER_UNKNOWN, DirectAeronCapacityOutcome.RESULT_UNKNOWN) },
            samples.count { it.outcome == DirectAeronCapacityOutcome.ACCEPTED },
            samples.count { it.outcome == DirectAeronCapacityOutcome.REJECTED },
            peakInFlight,
            duplicates,
            loadFailure,
            closeFailure,
            errors.toList(),
            errorOverflow,
            samples,
        )
    }
}
