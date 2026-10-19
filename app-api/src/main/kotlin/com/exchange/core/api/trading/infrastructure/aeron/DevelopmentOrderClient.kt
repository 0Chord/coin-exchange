package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageException
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.exceptions.AeronException
import org.agrona.concurrent.SleepingMillisIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal sealed interface OrderRequestOutcome {
    data class Confirmed(
        val result: AdmissionResultPayload,
    ) : OrderRequestOutcome

    data class Unconfirmed(
        val reason: String,
        val transportAccepted: Boolean,
    ) : OrderRequestOutcome
}

/** 한 번에 주문 요청 하나를 다룬다. 결과 대기 종료는 주문 취소가 아니다. */
internal class DevelopmentOrderClient(
    private val config: DevelopmentFundingSettings,
    private val acceptResponse: (AdmissionResultPayload) -> Boolean = { true },
) : AutoCloseable {
    private val commandCodec = AdmissionCommandCodec()
    private val resultCodec = AdmissionResultCodec()
    private val idle = SleepingMillisIdleStrategy(1)
    private val transportFailure = AtomicReference<Throwable?>()
    private var pending: AdmissionCommandPayload? = null
    private var received: AdmissionResultPayload? = null
    private var accepted = false
    private var lastKeepAlive = System.nanoTime()
    var diagnostic: String? = null
        private set
    internal val connection: AeronCluster =
        AeronCluster.connect(
            AeronCluster
                .Context()
                // conductor는 실패를 알리기만 한다. 결과 판단과 정리는 요청 스레드가 맡는다.
                .errorHandler { transportFailure.compareAndSet(null, it) }
                .aeronDirectoryName(config.driverDirectory)
                .ingressChannel("aeron:udp?term-length=64k")
                .ingressEndpoints("0=${config.endpoint(0)}")
                .egressChannel(config.udp(7))
                .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.startupTimeoutMs))
                .egressListener { _, _, buffer, offset, length, _ ->
                    val command = pending
                    if (command != null) {
                        try {
                            if (length !in 8..4096) throw AdmissionMessageException("결과 길이가 잘못됐습니다")
                            val bytes = ByteArray(length)
                            buffer.getBytes(offset, bytes)
                            val result = resultCodec.decode(bytes)
                            val matches =
                                result.userId == command.order.userId.value &&
                                    result.requestId == command.requestId &&
                                    result.marketId == command.order.marketId.value &&
                                    result.orderId == command.order.orderId.value
                            if (matches) {
                                if (acceptResponse(result)) received = result
                            } else {
                                diagnostic = "대기 중인 주문 원본과 다른 응답입니다"
                            }
                        } catch (error: AdmissionMessageException) {
                            diagnostic = "응답 복원 실패: ${error.message}"
                        }
                    }
                },
        )

    /** 양수 반환은 전송 수용이다. 이 메서드가 끝나도 결과를 수신한 것은 아니다. */
    fun send(command: AdmissionCommandPayload): Boolean {
        check(pending == null) { "기존 요청의 결과 대기를 먼저 끝내세요" }
        val bytes = commandCodec.encode(command)
        pending = command
        received = null
        accepted = false
        diagnostic = null
        val buffer = UnsafeBuffer(bytes)
        val startNs = System.nanoTime()
        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(config.resultTimeoutMs)
        var attempts = 0
        while (attempts++ < config.maxReplyAttempts && System.nanoTime() - startNs < timeoutNs) {
            if (transportFailure.get() != null) {
                diagnostic = "연결 오류가 기록된 채널입니다"
                return false
            }
            val position = connection.offer(buffer, 0, bytes.size)
            if (position > 0) {
                accepted = true
                return true
            }
            if (position != Publication.BACK_PRESSURED && position != Publication.ADMIN_ACTION) {
                diagnostic =
                    when (position) {
                        Publication.NOT_CONNECTED -> "연결 문제: NOT_CONNECTED, 전송 수용을 확인하지 못했습니다"
                        Publication.CLOSED -> "연결 문제: CLOSED, 닫힌 전송 채널입니다"
                        Publication.MAX_POSITION_EXCEEDED -> "전송 채널 종단 오류: MAX_POSITION_EXCEEDED, 같은 publication을 재시도하지 않습니다"
                        else -> "지원하지 않는 전송 반환값: $position"
                    }
                return false
            }
            poll()
            idle.idle()
        }
        diagnostic = "전송 재시도 상한에 도달했습니다"
        return false
    }

    fun awaitResult(): OrderRequestOutcome {
        check(pending != null) { "보낸 주문 요청이 없습니다" }
        try {
            if (!accepted) return unconfirmed()
            val startNs = System.nanoTime()
            val timeoutNs = TimeUnit.MILLISECONDS.toNanos(config.resultTimeoutMs)
            while (System.nanoTime() - startNs < timeoutNs) {
                received?.let { return OrderRequestOutcome.Confirmed(it) }
                if (transportFailure.get() != null || connection.isClosed) break
                poll()
                received?.let { return OrderRequestOutcome.Confirmed(it) }
                idle.idle()
            }
            return received?.let { OrderRequestOutcome.Confirmed(it) } ?: unconfirmed()
        } catch (error: AeronException) {
            return received?.let { OrderRequestOutcome.Confirmed(it) } ?: unconfirmed(error)
        } finally {
            pending = null
        }
    }

    fun request(
        command: AdmissionCommandPayload,
        onTransportAccepted: () -> Unit = {},
    ): OrderRequestOutcome =
        try {
            if (send(command)) onTransportAccepted()
            awaitResult()
        } catch (error: AeronException) {
            pending = null
            unconfirmed(error)
        }

    private fun unconfirmed(error: Throwable? = transportFailure.get()): OrderRequestOutcome.Unconfirmed {
        val reason =
            if (error != null) {
                "연결 문제로 결과 미확인: ${error.javaClass.simpleName}: ${error.message}"
            } else {
                diagnostic ?: "결과 미확인"
            }
        return OrderRequestOutcome.Unconfirmed("$reason. 같은 사용자·요청 ID와 주문 원본으로 다시 확인하세요", accepted)
    }

    private fun poll() {
        connection.pollEgress()
        val now = System.nanoTime()
        if (now - lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(config.keepAliveMs)) {
            connection.sendKeepAlive()
            lastKeepAlive = now
        }
    }

    override fun close() {
        try {
            connection.close()
        } finally {
            connection.context().close()
        }
    }
}

/** 기본 HTTP와 독립된 LIMIT+GTC 접수 도구. 가격·수량은 기존 최소 단위로 입력한다. */
fun main(args: Array<String>) {
    require(args.size == 10) { "사용법: 노드루트 기준포트 요청ID 사용자 주문ID BUY|SELL 가격최소단위 수량최소단위 원장ID 업무Instant" }
    val config = DevelopmentFundingSettings(Path.of(args[0]), args[1].toInt())
    val command =
        AdmissionCommandPayload(
            args[2],
            OrderAdmissionInput(
                config.market.marketId,
                OrderId(args[4]),
                UserId(args[3]),
                Side.valueOf(args[5]),
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(args[6].toLong()),
                Quantity(args[7].toLong()),
            ),
            config.market,
            config.feePolicySnapshot,
            args[8],
            Instant.parse(args[9]),
        )
    val client =
        try {
            DevelopmentOrderClient(config)
        } catch (error: AeronException) {
            println("UNCONFIRMED transportAccepted=false 연결 문제: ${error.message}")
            throw IllegalStateException("미접수로 단정하지 마세요. 같은 사용자·요청 ID와 주문 원본으로 재확인해야 합니다", error)
        }
    client.use {
        when (val outcome = it.request(command) { println("TRANSPORT_ACCEPTED 결과 대기") }) {
            is OrderRequestOutcome.Confirmed -> {
                println("RESULT_RECEIVED ${outcome.result}")
                println("접수 결과이며 체결·정산 완료가 아닙니다")
            }

            is OrderRequestOutcome.Unconfirmed -> {
                println("UNCONFIRMED transportAccepted=${outcome.transportAccepted} ${outcome.reason}")
                throw IllegalStateException("미접수로 단정하지 마세요. 같은 사용자·요청 ID와 주문 원본으로 재확인해야 합니다")
            }
        }
    }
}
